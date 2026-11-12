// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.SourceQueryValidator.ValidatedSourceQuery;
import io.trailkeep.importer.SourceQueryValidator.Vendor;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The importer's only door to a legacy database (WP-26 part n, design §4.1; ADR-0014 Part B amendment). Native SQL by
 * necessity: the source is a foreign schema no entity maps, and the queries are the operator's own, from the reviewed
 * mapping. It runs nothing but a {@link ValidatedSourceQuery}, with values only as bind parameters, never wrapped or
 * rewritten, in one read-only snapshot per session, with a statement timeout. A driver failure becomes a code-only
 * {@link SourceQueryException}. PostgreSQL sources are supported here; Oracle and SQL Server arrive with 26o and 26p.
 */
@Component
class SourceQueryJdbcDao {

    /** Rows fetched per round trip; no stage holds more than one batch (design §8.2). */
    static final int FETCH_SIZE = 500;

    /**
     * Where a legacy database is and who reads it: the read-only source account of the grants recipe
     * (docs/importer/source-accounts.md). The password comes from a Secret file and is never logged.
     *
     * @param url the JDBC URL, with verified TLS
     * @param user the source account
     * @param password its password
     * @param vendor the source's vendor
     */
    record Source(String url, String user, String password, Vendor vendor) {

        @Override
        public String toString() {
            return "Source[" + vendor + "]";
        }
    }

    /** One read-only snapshot of a source; closing it rolls back and disconnects. */
    static final class Session implements AutoCloseable {

        private final Connection connection;
        private final Vendor vendor;

        private Session(Connection connection, Vendor vendor) {
            this.connection = connection;
            this.vendor = vendor;
        }

        @Override
        public void close() {
            try (connection) {
                connection.rollback();
            } catch (SQLException e) {
                throw SourceQueryException.of(e);
            }
        }
    }

    /**
     * Connects and opens the snapshot every query of the session reads: PostgreSQL {@code REPEATABLE READ READ ONLY}.
     *
     * @param source the source
     * @return the session
     * @throws SourceQueryException if the source cannot be reached or refuses the account
     */
    Session open(Source source) {
        if (source.vendor() != Vendor.POSTGRESQL) {
            throw new UnsupportedOperationException(source.vendor() + " sources arrive with their adapter (26o, 26p)");
        }
        if (!SourceUrl.allowed(source.vendor(), source.url())) {
            throw new IllegalArgumentException(
                    "the source URL names another driver or a parameter outside the allow-list");
        }
        try {
            Connection connection = DriverManager.getConnection(source.url(), source.user(), source.password());
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            return new Session(connection, source.vendor());
        } catch (SQLException e) {
            throw SourceQueryException.of(e);
        }
    }

    /**
     * What the snapshot is, for {@code IMPORT_RUN.SOURCE_FINGERPRINT}: PostgreSQL's current snapshot.
     *
     * @param session the session
     * @return the fingerprint
     */
    String fingerprint(Session session) {
        // A fixed statement of the engine's own; nothing in it comes from the mapping or from data.
        try (PreparedStatement statement = session.connection.prepareStatement("SELECT pg_current_snapshot()::text");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getString(1);
        } catch (SQLException e) {
            throw SourceQueryException.of(e);
        }
    }

    /**
     * {@code validate-mapping}'s test fetch (design §4.1): runs the query for at most one row instead of asking for a
     * plan, which Oracle would write to {@code PLAN_TABLE} and SQL Server cannot give without its own permission. Every
     * bind variable is null, so a keyed query (an attachment's content query) proves it runs without reading content.
     * A failure is rolled back to a savepoint, so the session's later test fetches still run.
     *
     * @param session the session
     * @param query the validated query
     * @param timeout the statement timeout
     * @return how many rows came back: 0 or 1
     * @throws SourceQueryException if the query fails
     */
    int testFetch(Session session, ValidatedSourceQuery query, Duration timeout) {
        int[] count = {0};
        try {
            Savepoint before = session.connection.setSavepoint();
            try {
                run(session, query, null, timeout, 1, row -> count[0]++);
            } catch (SourceQueryException e) {
                session.connection.rollback(before);
                throw e;
            }
        } catch (SQLException e) {
            throw SourceQueryException.of(e);
        }
        return count[0];
    }

    /**
     * Streams a query's rows, one batch at a time, each as column name in lower case to value.
     *
     * @param session the session
     * @param query the validated query
     * @param binds its bind values, in order
     * @param timeout the statement timeout
     * @param rows what receives each row
     * @throws SourceQueryException if the query fails
     */
    void stream(
            Session session,
            ValidatedSourceQuery query,
            List<?> binds,
            Duration timeout,
            Consumer<Map<String, @Nullable Object>> rows) {
        run(session, query, binds, timeout, 0, rows);
    }

    /**
     * Fetches at most a few rows of a keyed query: an attachment's content query (design §4.3), whose caller tells
     * one row from two.
     *
     * @param session the session
     * @param query the validated query
     * @param binds its bind values, in order
     * @param timeout the statement timeout
     * @param maxRows the most rows read
     * @return the rows, each as column name in lower case to value
     * @throws SourceQueryException if the query fails
     */
    List<Map<String, @Nullable Object>> fetch(
            Session session, ValidatedSourceQuery query, List<?> binds, Duration timeout, int maxRows) {
        List<Map<String, @Nullable Object>> rows = new ArrayList<>();
        run(session, query, binds, timeout, maxRows, rows::add);
        return rows;
    }

    /** Runs a query; with {@code binds} null, every bind variable is set to null. */
    private void run(
            Session session,
            ValidatedSourceQuery query,
            @Nullable List<?> binds,
            Duration timeout,
            int maxRows,
            Consumer<Map<String, @Nullable Object>> rows) {
        if (query.vendor() != session.vendor) {
            throw new IllegalArgumentException("the query was validated for another vendor");
        }
        try (PreparedStatement statement = session.connection.prepareStatement(query.sql())) {
            statement.setQueryTimeout((int) Math.max(1, timeout.toSeconds()));
            statement.setFetchSize(FETCH_SIZE);
            statement.setMaxRows(maxRows);
            if (binds == null) {
                for (int i = 1; i <= statement.getParameterMetaData().getParameterCount(); i++) {
                    statement.setNull(i, Types.NULL);
                }
            } else {
                for (int i = 0; i < binds.size(); i++) {
                    statement.setObject(i + 1, binds.get(i));
                }
            }
            try (ResultSet result = statement.executeQuery()) {
                ResultSetMetaData columns = result.getMetaData();
                while (result.next()) {
                    Map<String, @Nullable Object> row = new HashMap<>();
                    for (int column = 1; column <= columns.getColumnCount(); column++) {
                        row.put(
                                columns.getColumnLabel(column).toLowerCase(Locale.ROOT),
                                value(result, columns, column));
                    }
                    rows.accept(row);
                }
            }
        } catch (SQLException e) {
            throw SourceQueryException.of(e);
        }
    }

    /**
     * A value as the transforms expect it (design §4.1 "Time zones"): zone-less times as {@link LocalDateTime}, never
     * through {@code getTimestamp}, which applies the JVM zone; zoned ones as {@link OffsetDateTime}; days as
     * {@link LocalDate}; {@code bytea} materialised per value (§4.3); everything else as the driver gives it.
     */
    private static @Nullable Object value(ResultSet result, ResultSetMetaData columns, int column) throws SQLException {
        // PostgreSQL names its types in lower case.
        Object value = switch (columns.getColumnTypeName(column)) {
            case "timestamp" -> result.getObject(column, LocalDateTime.class);
            case "timestamptz" -> result.getObject(column, OffsetDateTime.class);
            case "date" -> result.getObject(column, LocalDate.class);
            case "bytea" -> result.getBytes(column);
            default -> result.getObject(column);
        };
        return result.wasNull() ? null : value;
    }
}
