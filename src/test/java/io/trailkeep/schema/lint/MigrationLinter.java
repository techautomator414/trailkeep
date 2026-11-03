// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema.lint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks one migration script against the portability rules: identifiers of at most 30 characters, no reserved word
 * of either vendor as an identifier, no quoted identifiers, no vendor-specific type outside a {@code ${placeholder}},
 * and no character column wider than 1000. Comments, string literals and placeholders are masked (with offsets
 * preserved for line numbers) before any rule runs, so they can neither trigger nor hide a finding.
 *
 * <p>This is a lexical lint for the DDL subset the project writes (CREATE TABLE/INDEX/SEQUENCE/VIEW, ALTER TABLE
 * ADD/RENAME, inline and named constraints); it does not try to parse arbitrary SQL.
 */
final class MigrationLinter {

    /** Oracle's historical identifier limit, kept as a convention (BUILD_PLAN §8 rule 3). */
    static final int MAX_IDENTIFIER_LENGTH = 30;

    /** Widest portable character column (BUILD_PLAN §8 rule 2). */
    static final int MAX_VARCHAR_LENGTH = 1000;

    /** What a finding violates. */
    enum Rule {
        IDENTIFIER_TOO_LONG,
        RESERVED_WORD,
        QUOTED_IDENTIFIER,
        VENDOR_TYPE,
        VARCHAR_TOO_WIDE
    }

    /**
     * One finding.
     *
     * @param source script name
     * @param line 1-based line
     * @param rule the rule broken
     * @param token the offending identifier, type or declaration, upper case
     */
    record Violation(String source, int line, Rule rule, String token) {
        @Override
        public String toString() {
            return source + ":" + line + " " + rule + " " + token;
        }
    }

    private static final String NAME = "[A-Za-z_][A-Za-z0-9_$#]*";
    private static final String IDENT = "(" + NAME + "(?:\\." + NAME + ")?)";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE;

    private static final Pattern CREATE_TABLE =
            Pattern.compile("\\bCREATE\\s+(?:GLOBAL\\s+TEMPORARY\\s+)?TABLE\\s+" + IDENT + "\\s*\\(", FLAGS);
    private static final Pattern ALTER_ADD_LIST =
            Pattern.compile("\\bALTER\\s+TABLE\\s+" + IDENT + "\\s+ADD\\s*\\(", FLAGS);
    private static final List<Pattern> NAMED_OBJECTS = List.of(
            Pattern.compile("\\bCONSTRAINT\\s+" + IDENT, FLAGS),
            Pattern.compile("\\bCREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+" + IDENT, FLAGS),
            Pattern.compile("\\bCREATE\\s+SEQUENCE\\s+" + IDENT, FLAGS),
            Pattern.compile("\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?VIEW\\s+" + IDENT, FLAGS),
            Pattern.compile("\\bRENAME\\s+(?:COLUMN\\s+" + NAME + "\\s+)?TO\\s+" + IDENT, FLAGS));
    /** {@code ALTER TABLE t ADD [COLUMN] x}; x may also be a keyword starting a constraint clause. */
    private static final Pattern ALTER_ADD_COLUMN = Pattern.compile(
            "\\bALTER\\s+TABLE\\s+" + NAME + "(?:\\." + NAME + ")?\\s+ADD\\s+(?:COLUMN\\s+)?" + IDENT, FLAGS);

    private static final Set<String> NOT_A_COLUMN = Set.of("CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK");

    private static final Pattern VENDOR_TYPES = Pattern.compile(
            "\\b(BIGINT|BOOLEAN|TEXT|BYTEA|BLOB|CLOB|NCLOB|TIMESTAMPTZ|TIMESTAMP|VARCHAR2|NVARCHAR2|NUMBER|SERIAL"
                    + "|BIGSERIAL|SMALLSERIAL|JSONB|JSON|UUID|DATETIME|TINYINT|BINARY_FLOAT|BINARY_DOUBLE|RAW)\\b",
            FLAGS);
    private static final Pattern CHARACTER_TYPE =
            Pattern.compile("\\b(N?VARCHAR2?|N?CHAR|CHARACTER\\s+VARYING)\\s*\\(\\s*(\\d+)", FLAGS);
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

    private final Set<String> reservedWords;

    MigrationLinter(Set<String> reservedWords) {
        this.reservedWords = Set.copyOf(reservedWords);
    }

    List<Violation> lint(String source, String sql) {
        String masked = mask(sql);
        List<Violation> violations = new ArrayList<>();
        Matcher quoted = QUOTED.matcher(masked);
        while (quoted.find()) {
            violations.add(new Violation(source, line(sql, quoted.start()), Rule.QUOTED_IDENTIFIER, quoted.group()));
        }
        String unquoted = blank(masked, QUOTED);
        checkIdentifiers(source, sql, unquoted, violations);
        checkTypes(source, sql, unquoted, violations);
        return List.copyOf(violations);
    }

    private void checkIdentifiers(String source, String sql, String masked, List<Violation> out) {
        for (Pattern pattern : NAMED_OBJECTS) {
            Matcher matcher = pattern.matcher(masked);
            while (matcher.find()) {
                checkIdentifier(source, line(sql, matcher.start(1)), matcher.group(1), out);
            }
        }
        Matcher added = ALTER_ADD_COLUMN.matcher(masked);
        while (added.find()) {
            if (!NOT_A_COLUMN.contains(added.group(1).toUpperCase(Locale.ROOT))) {
                checkIdentifier(source, line(sql, added.start(1)), added.group(1), out);
            }
        }
        for (Pattern pattern : List.of(CREATE_TABLE, ALTER_ADD_LIST)) {
            Matcher matcher = pattern.matcher(masked);
            while (matcher.find()) {
                if (pattern == CREATE_TABLE) {
                    checkIdentifier(source, line(sql, matcher.start(1)), matcher.group(1), out);
                }
                checkColumnList(source, sql, masked, matcher.end() - 1, out);
            }
        }
    }

    /** Checks the first word of every top-level element in the parenthesised list opening at {@code open}. */
    private void checkColumnList(String source, String sql, String masked, int open, List<Violation> out) {
        int depth = 0;
        int elementStart = open + 1;
        for (int i = open; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' || (c == ',' && depth == 1)) {
                if (depth == 1) {
                    checkColumn(source, sql, masked, elementStart, i, out);
                    elementStart = i + 1;
                }
                if (c == ')' && --depth == 0) {
                    return;
                }
            }
        }
    }

    private void checkColumn(String source, String sql, String masked, int start, int end, List<Violation> out) {
        Matcher first = Pattern.compile("\\s*(" + NAME + ")").matcher(masked).region(start, end);
        if (first.lookingAt() && !NOT_A_COLUMN.contains(first.group(1).toUpperCase(Locale.ROOT))) {
            checkIdentifier(source, line(sql, first.start(1)), first.group(1), out);
        }
    }

    private void checkIdentifier(String source, int line, String qualified, List<Violation> out) {
        String name = qualified.substring(qualified.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
        if (name.length() > MAX_IDENTIFIER_LENGTH) {
            out.add(new Violation(source, line, Rule.IDENTIFIER_TOO_LONG, name));
        }
        if (reservedWords.contains(name)) {
            out.add(new Violation(source, line, Rule.RESERVED_WORD, name));
        }
    }

    private static void checkTypes(String source, String sql, String masked, List<Violation> out) {
        Matcher type = VENDOR_TYPES.matcher(masked);
        while (type.find()) {
            out.add(new Violation(
                    source,
                    line(sql, type.start()),
                    Rule.VENDOR_TYPE,
                    type.group(1).toUpperCase(Locale.ROOT)));
        }
        Matcher character = CHARACTER_TYPE.matcher(masked);
        while (character.find()) {
            if (Long.parseLong(character.group(2)) > MAX_VARCHAR_LENGTH) {
                String declared = character.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ") + "("
                        + character.group(2) + ")";
                out.add(new Violation(source, line(sql, character.start()), Rule.VARCHAR_TOO_WIDE, declared));
            }
        }
    }

    /** Replaces comments, string literals and {@code ${placeholders}} with spaces, keeping offsets and newlines. */
    static String mask(String sql) {
        StringBuilder out = new StringBuilder(sql);
        int i = 0;
        while (i < sql.length()) {
            int end;
            if (sql.startsWith("--", i)) {
                end = indexOrEnd(sql, "\n", i);
            } else if (sql.startsWith("/*", i)) {
                end = indexOrEnd(sql, "*/", i + 2) + 2;
            } else if (sql.charAt(i) == '\'') {
                end = closingQuote(sql, i + 1);
            } else if (sql.startsWith("${", i)) {
                end = indexOrEnd(sql, "}", i) + 1;
            } else {
                i++;
                continue;
            }
            end = Math.min(end, sql.length());
            for (int j = i; j < end; j++) {
                if (out.charAt(j) != '\n') {
                    out.setCharAt(j, ' ');
                }
            }
            i = end;
        }
        return out.toString();
    }

    private static int closingQuote(String sql, int from) {
        int i = from;
        while (i < sql.length()) {
            if (sql.charAt(i) == '\'') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return sql.length();
    }

    private static int indexOrEnd(String sql, String token, int from) {
        int index = sql.indexOf(token, from);
        return index < 0 ? sql.length() : index;
    }

    private static String blank(String text, Pattern pattern) {
        StringBuilder out = new StringBuilder(text);
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            for (int j = matcher.start(); j < matcher.end(); j++) {
                out.setCharAt(j, ' ');
            }
        }
        return out.toString();
    }

    private static int line(String sql, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (sql.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
