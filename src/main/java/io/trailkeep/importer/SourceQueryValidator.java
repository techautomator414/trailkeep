// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.MappingProblem.Code;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.jspecify.annotations.Nullable;

/**
 * Restricts a mapping's source queries (WP-26 part m, design §4.1, §10; ADR-0014 Part B amendment). A query is parsed
 * with JSqlParser and must be exactly one {@code SELECT} or {@code WITH … SELECT}: no statement separator, no {@code
 * INTO}, no {@code FOR UPDATE} or locking hint, no table function such as {@code OPENROWSET}, no Oracle {@code @dblink}
 * and no function outside the vendor's allow-list of pure built-ins. Oracle also calls a parameterless function
 * without parentheses ({@code SOME_PACKAGE.SOME_FUNCTION} reads like a qualified column), so on Oracle every qualifier
 * of a column must name a table, alias, subquery or {@code WITH} item of the statement (26o). A query the parser cannot
 * read is refused, never passed. The query is never rewritten; the grants recipe stays the control for which tables
 * it can read.
 */
final class SourceQueryValidator {

    /** The legacy databases a JDBC source can be. */
    enum Vendor {
        SQLSERVER,
        ORACLE,
        POSTGRESQL
    }

    /**
     * A query that passed: the only type the source DAO runs (26n), so nothing unvalidated reaches a legacy database.
     * Only {@link #validate} makes one.
     */
    static final class ValidatedSourceQuery {

        private final String sql;
        private final Vendor vendor;

        private ValidatedSourceQuery(String sql, Vendor vendor) {
            this.sql = sql;
            this.vendor = vendor;
        }

        /**
         * The query exactly as the mapping holds it.
         *
         * @return the SQL
         */
        String sql() {
            return sql;
        }

        /**
         * The vendor it was validated for.
         *
         * @return the vendor
         */
        Vendor vendor() {
            return vendor;
        }
    }

    /**
     * The outcome: a validated query, or the codes that refuse it.
     *
     * @param query the validated query, or {@code null} when refused
     * @param problems the refusal codes, sorted; empty when validated
     */
    record Result(@Nullable ValidatedSourceQuery query, List<MappingProblem.Code> problems) {}

    /**
     * Pure built-ins every vendor has: string, numeric, null handling, aggregates, and the window functions a recipe
     * uses to pick one row per key. A window function is name-checked like any other call.
     */
    private static final Set<String> COMMON = Set.of(
            "COALESCE",
            "NULLIF",
            "UPPER",
            "LOWER",
            "TRIM",
            "LTRIM",
            "RTRIM",
            "REPLACE",
            "CONCAT",
            "SUBSTRING",
            "ROUND",
            "FLOOR",
            "CEILING",
            "ABS",
            "COUNT",
            "MIN",
            "MAX",
            "SUM",
            "ROW_NUMBER",
            "RANK",
            "DENSE_RANK",
            "LAG",
            "LEAD",
            "FIRST_VALUE",
            "LAST_VALUE");

    /** Per vendor, the date, string and JSON-construction built-ins the export recipes use. */
    private static final Map<Vendor, Set<String>> VENDOR = Map.of(
            Vendor.SQLSERVER,
            Set.of(
                    "ISNULL",
                    "LEN",
                    "LEFT",
                    "RIGHT",
                    "CHARINDEX",
                    "CONVERT",
                    "DATEADD",
                    "DATEDIFF",
                    "DATEPART",
                    "FORMAT",
                    "STRING_AGG"),
            Vendor.ORACLE,
            Set.of(
                    "NVL",
                    "NVL2",
                    "DECODE",
                    "SUBSTR",
                    "LENGTH",
                    "INSTR",
                    "LPAD",
                    "RPAD",
                    "TO_CHAR",
                    "TO_DATE",
                    "TO_NUMBER",
                    "TRUNC",
                    "CEIL",
                    "JSON_OBJECT",
                    "JSON_ARRAYAGG",
                    "LISTAGG"),
            Vendor.POSTGRESQL,
            Set.of(
                    "LENGTH",
                    "CHAR_LENGTH",
                    "LEFT",
                    "RIGHT",
                    "SUBSTR",
                    "POSITION",
                    "LPAD",
                    "RPAD",
                    "TO_CHAR",
                    "TO_DATE",
                    "DATE_TRUNC",
                    "CEIL",
                    "STRING_AGG",
                    "JSON_BUILD_OBJECT",
                    "JSONB_BUILD_OBJECT",
                    "JSON_AGG"));

    /** SQL Server table hints that lock; the parser reads few hints, so the deparsed text is checked too. */
    private static final Pattern LOCKING_HINT = Pattern.compile(
            "\\b(UPDLOCK|XLOCK|HOLDLOCK|TABLOCKX?|ROWLOCK|PAGLOCK|SERIALIZABLE)\\b", Pattern.CASE_INSENSITIVE);

    private SourceQueryValidator() {}

    /**
     * Validates one query for a vendor.
     *
     * @param sql the query from the mapping
     * @param vendor the source's vendor
     * @return the validated query, or why it is refused
     */
    static Result validate(String sql, Vendor vendor) {
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException | RuntimeException unreadable) {
            // The parser's message quotes the query; only the fact is kept.
            return refused(Code.QUERY_UNPARSEABLE);
        }
        if (statements.size() != 1) {
            return refused(Code.QUERY_NOT_ONE_STATEMENT);
        }
        Statement statement = statements.get(0);
        if (!(statement instanceof Select select)) {
            return refused(Code.QUERY_NOT_SELECT);
        }
        Checker checker = new Checker(vendor);
        checker.check(select);
        if (vendor == Vendor.ORACLE && !checker.names.containsAll(checker.qualifiers)) {
            checker.problems.add(Code.QUERY_FUNCTION_NOT_ALLOWED);
        }
        if (LOCKING_HINT.matcher(select.toString()).find()) {
            checker.problems.add(Code.QUERY_LOCKS);
        }
        return checker.problems.isEmpty()
                ? new Result(new ValidatedSourceQuery(sql, vendor), List.of())
                : new Result(null, List.copyOf(checker.problems));
    }

    private static Result refused(Code code) {
        return new Result(null, List.of(code));
    }

    /** Walks the whole statement, subqueries and expressions included, and records what is not allowed. */
    private static final class Checker extends TablesNamesFinder<Void> {

        private final Set<String> allowed = new TreeSet<>(COMMON);
        private final Set<Code> problems = new TreeSet<>();
        private final Set<String> names = new TreeSet<>();
        private final Set<String> qualifiers = new TreeSet<>();

        Checker(Vendor vendor) {
            allowed.addAll(VENDOR.get(vendor));
            init(false);
        }

        void check(Select select) {
            select.accept((net.sf.jsqlparser.statement.select.SelectVisitor<Void>) this, null);
        }

        @Override
        public <S> Void visit(PlainSelect select, S context) {
            if (select.getIntoTables() != null
                    || select.getIntoTempTable() != null
                    || select.getMySqlSelectIntoClause() != null) {
                problems.add(Code.QUERY_INTO);
            }
            if (select.getForMode() != null) {
                problems.add(Code.QUERY_LOCKS);
            }
            return super.visit(select, context);
        }

        @Override
        public <S> Void visit(Table table, S context) {
            // JSqlParser's getDBLinkName() answers the table name when there is no link, so the name itself is checked.
            if (table.getFullyQualifiedName().contains("@")) {
                problems.add(Code.QUERY_DATABASE_LINK);
            }
            // Column processing is off (init(false)), so only tables the statement reads arrive here.
            name(table.getName());
            name(table.getAlias() == null ? null : table.getAlias().getName());
            return super.visit(table, context);
        }

        @Override
        public <S> Void visit(ParenthesedSelect select, S context) {
            name(select.getAlias() == null ? null : select.getAlias().getName());
            return super.visit(select, context);
        }

        /** A column's qualifier: its table part, which on Oracle may be a package called without parentheses. */
        @Override
        public <S> Void visit(Column column, S context) {
            if (column.getTable() != null && column.getTable().getName() != null) {
                qualifiers.add(normalised(column.getTable().getName()));
            }
            return super.visit(column, context);
        }

        @Override
        public <S> Void visit(TableFunction function, S context) {
            problems.add(Code.QUERY_TABLE_FUNCTION);
            return super.visit(function, context);
        }

        /** A data-modifying CTE ({@code WITH x AS (DELETE … RETURNING …) SELECT …}) is not a read: refused unwalked. */
        @Override
        public <S> Void visit(WithItem<?> withItem, S context) {
            if (!(withItem.getParenthesedStatement() instanceof ParenthesedSelect)) {
                problems.add(Code.QUERY_NOT_SELECT);
                return null;
            }
            name(withItem.getAlias() == null ? null : withItem.getAlias().getName());
            return super.visit(withItem, context);
        }

        /** {@code name(...) OVER (...)} parses as an analytic expression, not a {@link Function}; same allow-list. */
        @Override
        public <S> Void visit(AnalyticExpression expression, S context) {
            String name = expression.getName();
            if (name == null || !allowed.contains(name.toUpperCase(Locale.ROOT))) {
                problems.add(Code.QUERY_FUNCTION_NOT_ALLOWED);
            }
            return super.visit(expression, context);
        }

        @Override
        public <S> Void visit(Function function, S context) {
            List<String> name = function.getMultipartName() == null ? new ArrayList<>() : function.getMultipartName();
            if (name.size() != 1 || !allowed.contains(name.get(0).toUpperCase(Locale.ROOT))) {
                problems.add(Code.QUERY_FUNCTION_NOT_ALLOWED);
            }
            return super.visit(function, context);
        }

        private void name(@Nullable String name) {
            if (name != null) {
                names.add(normalised(name));
            }
        }

        /** Oracle folds unquoted names to upper case; a quoted one is compared as written, quotes removed. */
        private static String normalised(String name) {
            return name.startsWith("\"") ? name.replace("\"", "") : name.toUpperCase(Locale.ROOT);
        }
    }
}
