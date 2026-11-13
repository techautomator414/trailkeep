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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

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
     * Reads the forms of rows by id, a chunk of ids per statement, in the caller's transaction. The statement is a
     * {@code *JdbcDao}'s constant, taking the ids as {@code :ids}; nothing is built from data.
     *
     * @param jdbc the caller's template
     * @param sql the statement, selecting {@code key} and the columns
     * @param key the column the forms are keyed by
     * @param ids the ids to bind
     * @param params any other parameters the statement takes, constant ones
     * @param columns the columns of each form
     * @return each row's form, by its key; rows that do not exist are absent
     */
    public static Map<Long, String> read(
            NamedParameterJdbcTemplate jdbc,
            String sql,
            String key,
            Collection<Long> ids,
            Map<String, ?> params,
            Columns columns) {
        List<Long> all = List.copyOf(ids);
        Map<Long, String> forms = new HashMap<>();
        for (int from = 0; from < all.size(); from += IDS_PER_QUERY) {
            Map<String, Object> bound = new HashMap<>(params);
            bound.put("ids", all.subList(from, Math.min(all.size(), from + IDS_PER_QUERY)));
            jdbc.query(sql, bound, (RowCallbackHandler)
                    row -> forms.put(row.getLong(key), columns.add(of(row)).toString()));
        }
        return forms;
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
