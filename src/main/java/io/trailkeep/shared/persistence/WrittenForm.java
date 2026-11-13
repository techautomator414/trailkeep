// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * One row's values as the database holds them, as a canonical string (docs/design/importer.md §9): what the importer
 * hashes into the import ledger's written hash when it writes a row, and again when {@code reconcile} reads the row
 * back, so the two compare only if the row is unchanged. Built from a JDBC row, never from an entity, so it sees what
 * the database stored: Oracle reads an empty string back as {@code NULL}, so both are absent here; instants are kept to
 * the microsecond, the precision of {@code TIMESTAMP WITH TIME ZONE} on both vendors; numbers lose trailing zeros. Each
 * value is length-prefixed, so no value can imitate a boundary. CLOB columns are read whole, never through an SQL
 * function.
 */
public final class WrittenForm {

    /** Ids per {@code IN} list, well below Oracle's 1000. */
    private static final int IDS_PER_QUERY = 500;

    private final ResultSet row;
    private final StringBuilder form = new StringBuilder();

    private WrittenForm(ResultSet row) {
        this.row = row;
    }

    /**
     * A form of the current row of a result set.
     *
     * @param row the result set, positioned on the row
     * @return an empty form to add the row's columns to, in a fixed order
     */
    public static WrittenForm of(ResultSet row) {
        return new WrittenForm(row);
    }

    /** The columns of a row's form, added in a fixed order. */
    @FunctionalInterface
    public interface Columns {

        /**
         * Adds a row's columns.
         *
         * @param form the row's form, empty
         * @return the form
         * @throws SQLException if a column cannot be read
         */
        WrittenForm add(WrittenForm form) throws SQLException;
    }

    /**
     * Splits ids into the chunks a {@code *JdbcDao} binds to its constant statement's {@code IN} list, one statement
     * per chunk; this class runs no statement itself.
     *
     * @param ids the ids
     * @return the ids in chunks of at most 500, in their order
     */
    public static List<List<Long>> chunks(Collection<Long> ids) {
        List<Long> all = List.copyOf(ids);
        List<List<Long>> chunks = new ArrayList<>();
        for (int from = 0; from < all.size(); from += IDS_PER_QUERY) {
            chunks.add(all.subList(from, Math.min(all.size(), from + IDS_PER_QUERY)));
        }
        return chunks;
    }

    /**
     * A row handler for a {@code *JdbcDao}'s query that puts each row's form into a map.
     *
     * @param forms the map to fill, by key
     * @param key the column the forms are keyed by
     * @param columns the columns of each form
     * @return the handler
     */
    public static RowCallbackHandler into(Map<Long, String> forms, String key, Columns columns) {
        return row -> forms.put(row.getLong(key), columns.add(of(row)).toString());
    }

    /**
     * Adds text columns, {@code VARCHAR} or CLOB.
     *
     * @param columns their names
     * @return this form
     * @throws SQLException if a column cannot be read
     */
    public WrittenForm texts(String... columns) throws SQLException {
        for (String column : columns) {
            String value = row.getString(column);
            add('s', value == null || value.isEmpty() ? null : value);
        }
        return this;
    }

    /**
     * Adds numeric columns.
     *
     * @param columns their names
     * @return this form
     * @throws SQLException if a column cannot be read
     */
    public WrittenForm numbers(String... columns) throws SQLException {
        for (String column : columns) {
            BigDecimal value = row.getBigDecimal(column);
            add('n', value == null ? null : value.stripTrailingZeros().toPlainString());
        }
        return this;
    }

    /**
     * Adds {@code TIMESTAMP WITH TIME ZONE} columns, as microseconds since the epoch.
     *
     * @param columns their names
     * @return this form
     * @throws SQLException if a column cannot be read
     */
    public WrittenForm instants(String... columns) throws SQLException {
        for (String column : columns) {
            OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
            add('t', value == null ? null : Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, value.toInstant())));
        }
        return this;
    }

    /**
     * Adds {@code DATE} columns, as days.
     *
     * @param columns their names
     * @return this form
     * @throws SQLException if a column cannot be read
     */
    public WrittenForm dates(String... columns) throws SQLException {
        for (String column : columns) {
            LocalDate value = row.getObject(column, LocalDate.class);
            add('d', value == null ? null : value.toString());
        }
        return this;
    }

    private void add(char kind, @Nullable String value) {
        form.append(kind);
        if (value != null) {
            form.append(value.length()).append(':').append(value);
        }
        form.append(';');
    }

    @Override
    public String toString() {
        return form.toString();
    }
}
