// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.restoredrill;

import io.trailkeep.shared.persistence.DatabaseVendor;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.TestDatabaseUsers;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The restore drill on PostgreSQL 16: {@code pg_dump} in the custom format, a new database, {@code pg_restore} with four
 * jobs and {@code vacuumdb --analyze-in-stages} (a dump carries no planner statistics). The owner is the container's
 * superuser, so {@code pg_trgm} is restored by the dump itself; a deployment whose owner may not create it has a DBA
 * create it first (deploy/README.md, "Search indexes (V21)"). Opt-in: the {@code perf-it} profile's {@code
 * restore-drill} execution, which no workflow runs.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("restore-drill")
class PostgresRestoreDrillIT extends AbstractRestoreDrillIT {

    private static final String DUMP = "/tmp/trailkeep.dump";

    @Override
    String vendorProfile() {
        return "test";
    }

    @Override
    DatabaseVendor vendor() {
        return DatabaseVendor.POSTGRESQL;
    }

    @Override
    String title() {
        return "Restore drill, PostgreSQL 16 (" + PostgresTestcontainersConfiguration.IMAGE.asCanonicalNameString()
                + ")";
    }

    @Override
    String fileName() {
        return "restore-drill-postgresql.md";
    }

    @Override
    JdbcDatabaseContainer<?> newDatabase() {
        // pg_restore's parallel index builds use shared memory beyond Docker's 64 MB default.
        PostgreSQLContainer postgres =
                new PostgreSQLContainer(PostgresTestcontainersConfiguration.IMAGE).withSharedMemorySize(1L << 30);
        postgres.start();
        return postgres;
    }

    @Override
    Owner newOwner(JdbcDatabaseContainer<?> database, TestDatabaseUsers users) {
        return new Owner(database.getUsername(), database.getPassword(), "public");
    }

    @Override
    void seed(JdbcDatabaseContainer<?> database, Owner owner, Connection connection, DrillReport report)
            throws Exception {
        report.run(
                "500k seed applied (50 000 cases, 500 000 comments)",
                () -> new ResourceDatabasePopulator(
                                new FileSystemResource(Path.of("perf", "seed-cases-postgresql.sql")),
                                new FileSystemResource(Path.of("perf", "seed-comments-postgresql.sql")))
                        .populate(connection));
    }

    @Override
    String dumpPath(JdbcDatabaseContainer<?> database) {
        return DUMP;
    }

    @Override
    void backup(JdbcDatabaseContainer<?> database, Owner owner, String path) throws Exception {
        // The container's local socket trusts its own users; a deployment passes the password through ~/.pgpass.
        exec(database, "pg_dump", "-U", owner.username(), "-d", database.getDatabaseName(), "-Fc", "-f", path);
    }

    @Override
    void restore(JdbcDatabaseContainer<?> database, Owner owner, String path, DrillReport report) throws Exception {
        report.run(
                "pg_restore, 4 jobs",
                () -> exec(
                        database,
                        "pg_restore",
                        "-U",
                        owner.username(),
                        "-d",
                        database.getDatabaseName(),
                        "--exit-on-error",
                        "-j",
                        "4",
                        path));
        report.run(
                "vacuumdb --analyze-in-stages",
                () -> exec(
                        database,
                        "vacuumdb",
                        "-U",
                        owner.username(),
                        "-d",
                        database.getDatabaseName(),
                        "--analyze-in-stages",
                        "-j",
                        "4"));
    }

    @Override
    Map<String, String> indexState(JdbcDatabaseContainer<?> database, Connection owner) throws SQLException {
        Map<String, String> state = new LinkedHashMap<>();
        try (Statement statement = owner.createStatement()) {
            try (ResultSet index = statement.executeQuery("SELECT i.indisvalid, i.indisready, am.amname"
                    + " FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_am am ON am.oid = c.relam"
                    + " WHERE c.relname = 'ix_case_search_text'")) {
                state.put(
                        "index",
                        index.next()
                                ? index.getString(3)
                                        + (index.getBoolean(1) && index.getBoolean(2) ? " valid" : " NOT valid")
                                : "missing");
            }
            try (ResultSet extension =
                    statement.executeQuery("SELECT extversion FROM pg_extension WHERE extname = 'pg_trgm'")) {
                state.put("pg_trgm", extension.next() ? extension.getString(1) : "missing");
            }
            try (ResultSet found =
                    statement.executeQuery("SELECT COUNT(*) FROM CASE_SEARCH WHERE SEARCH_TEXT LIKE '%kestrel%'")) {
                found.next();
                state.put("kestrel", found.getString(1));
            }
        }
        return state;
    }

    @Override
    long databaseBytes(Connection owner) throws SQLException {
        try (Statement statement = owner.createStatement();
                ResultSet size = statement.executeQuery("SELECT pg_database_size(current_database())")) {
            size.next();
            return size.getLong(1);
        }
    }

    @Override
    Map<String, Long> sequences(Connection owner) throws SQLException {
        Map<String, Long> marks = new TreeMap<>();
        try (Statement statement = owner.createStatement();
                ResultSet rows = statement.executeQuery("SELECT sequencename, COALESCE(last_value, 0)"
                        + " FROM pg_sequences WHERE schemaname = 'public'")) {
            while (rows.next()) {
                marks.put(rows.getString(1), rows.getLong(2));
            }
        }
        return marks;
    }
}
