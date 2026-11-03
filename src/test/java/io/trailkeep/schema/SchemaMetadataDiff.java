// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema;

import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.SelectableMapping;
import org.hibernate.persister.entity.EntityPersister;
import org.jspecify.annotations.Nullable;

/**
 * Compares every entity column in the Hibernate mapping model with {@link DatabaseMetaData}. Hibernate
 * {@code validate} checks only that a column exists with a compatible type family; this adds nullability and, for
 * character columns, declared length — the two things that silently drift between an entity and its migration.
 */
public final class SchemaMetadataDiff {

    private static final Set<Integer> CHARACTER_TYPES =
            Set.of(Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR);

    private SchemaMetadataDiff() {}

    /**
     * A column as the entity mapping declares it.
     *
     * @param table upper-case table name
     * @param column upper-case column name
     * @param nullable whether the mapping allows null
     * @param length declared length, or {@code null} when the mapping has none
     */
    public record ExpectedColumn(String table, String column, boolean nullable, @Nullable Long length) {}

    /**
     * Collects the columns of every entity in the mapping model, identifiers included.
     *
     * @param entityManagerFactory the application's factory
     * @return expected columns keyed by {@code TABLE.COLUMN}, in mapping order
     */
    public static Map<String, ExpectedColumn> expectedColumns(EntityManagerFactory entityManagerFactory) {
        SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        Map<String, ExpectedColumn> columns = new LinkedHashMap<>();
        sessionFactory.getMappingMetamodel().forEachEntityDescriptor(persister -> collect(persister, columns));
        return columns;
    }

    private static void collect(EntityPersister persister, Map<String, ExpectedColumn> columns) {
        persister.getIdentifierMapping().forEachSelectable((index, selectable) -> add(selectable, columns));
        persister.forEachSelectable((index, selectable) -> add(selectable, columns));
    }

    private static void add(SelectableMapping selectable, Map<String, ExpectedColumn> columns) {
        if (selectable.isFormula()) {
            return;
        }
        String table = normalize(selectable.getContainingTableExpression());
        String column = normalize(selectable.getSelectionExpression());
        columns.putIfAbsent(
                table + "." + column, new ExpectedColumn(table, column, selectable.isNullable(), selectable.getLength()));
    }

    /**
     * Diffs the mapping against the live schema.
     *
     * @param entityManagerFactory the application's factory
     * @param dataSource the migrated database
     * @param schema the schema owning the tables, or {@code null} for the connection's current schema
     * @return every mismatch found; empty when entity and schema agree
     * @throws SQLException if the metadata cannot be read
     */
    public static List<ColumnMismatch> diff(
            EntityManagerFactory entityManagerFactory, DataSource dataSource, @Nullable String schema)
            throws SQLException {
        List<ColumnMismatch> mismatches = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String owner = schema != null ? schema : connection.getSchema();
            for (ExpectedColumn expected : expectedColumns(entityManagerFactory).values()) {
                compare(metaData, owner, expected, mismatches);
            }
        }
        return List.copyOf(mismatches);
    }

    private static void compare(
            DatabaseMetaData metaData, @Nullable String owner, ExpectedColumn expected, List<ColumnMismatch> out)
            throws SQLException {
        try (ResultSet rs = metaData.getColumns(
                null,
                pattern(metaData, owner),
                pattern(metaData, expected.table()),
                pattern(metaData, expected.column()))) {
            if (!rs.next()) {
                out.add(new ColumnMismatch(
                        expected.table(), expected.column(), ColumnMismatch.Kind.MISSING_COLUMN, "present", "absent"));
                return;
            }
            boolean nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
            if (nullable != expected.nullable()) {
                out.add(new ColumnMismatch(expected.table(), expected.column(), ColumnMismatch.Kind.NULLABILITY,
                        nullText(expected.nullable()), nullText(nullable)));
            }
            Long length = expected.length();
            int size = rs.getInt("COLUMN_SIZE");
            if (CHARACTER_TYPES.contains(rs.getInt("DATA_TYPE")) && length != null && length != size) {
                out.add(new ColumnMismatch(expected.table(), expected.column(), ColumnMismatch.Kind.LENGTH,
                        String.valueOf(length), String.valueOf(size)));
            }
        }
    }

    private static String nullText(boolean nullable) {
        return nullable ? "NULL" : "NOT NULL";
    }

    private static String normalize(String identifier) {
        String unqualified = identifier.substring(identifier.lastIndexOf('.') + 1);
        return unqualified.replace("\"", "").toUpperCase(Locale.ROOT);
    }

    /** Folds to the case the database stores unquoted identifiers in and escapes {@code _} and {@code %}. */
    private static @Nullable String pattern(DatabaseMetaData metaData, @Nullable String identifier)
            throws SQLException {
        if (identifier == null) {
            return null;
        }
        String folded = metaData.storesLowerCaseIdentifiers()
                ? identifier.toLowerCase(Locale.ROOT)
                : identifier.toUpperCase(Locale.ROOT);
        String escape = metaData.getSearchStringEscape();
        return folded.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }
}
