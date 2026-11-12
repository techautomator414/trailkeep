// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A PostgreSQL legacy database holding the empty tables {@code importer/example-mapping.yaml} reads, so a test can
 * run {@code validate-mapping}'s test fetch (WP-26 part n) against the example with its vendor set to PostgreSQL. It
 * reads as the container's own user; the source-account recipe itself is proven by {@link PostgresSourceQueryIT}.
 * Started once, on first use, and stopped with the JVM.
 */
final class ExampleLegacySource {

    private static final PostgreSQLContainer LEGACY =
            new PostgreSQLContainer(PostgresTestcontainersConfiguration.IMAGE);

    private static boolean ready;

    private ExampleLegacySource() {}

    /**
     * The {@code import.source.*} arguments of the example's source, starting the database on first use.
     *
     * @return {@code --import.source.url}, {@code --import.source.user} and {@code --import.source.password}
     * @throws SQLException if the tables cannot be created
     */
    static synchronized List<String> args() throws SQLException {
        if (!ready) {
            LEGACY.start();
            try (Connection admin = DriverManager.getConnection(
                            LEGACY.getJdbcUrl(), LEGACY.getUsername(), LEGACY.getPassword());
                    Statement statement = admin.createStatement()) {
                for (String sql : List.of(
                        "CREATE SCHEMA dbo",
                        "CREATE TABLE dbo.cases (case_id INTEGER, case_no TEXT, subject_line TEXT, details TEXT,"
                                + " unit TEXT, assignee TEXT, state TEXT, result TEXT, opened TIMESTAMP,"
                                + " closed TIMESTAMP, found_on DATE, confidential BOOLEAN, acl_entries TEXT,"
                                + " hold_flag BOOLEAN)",
                        "CREATE TABLE dbo.notes (note_id INTEGER, case_id INTEGER, author TEXT, created TIMESTAMP,"
                                + " body TEXT)",
                        "CREATE TABLE dbo.files (file_id INTEGER, case_id INTEGER, file_name TEXT, size_bytes BIGINT,"
                                + " uploaded TIMESTAMP, uploader TEXT, content BYTEA)",
                        "CREATE TABLE dbo.case_audit (audit_id INTEGER, case_id INTEGER, changed_at TIMESTAMP,"
                                + " changed_by TEXT, field_name TEXT, old_val TEXT, new_val TEXT)",
                        "CREATE TABLE dbo.users (user_id TEXT, display_name TEXT, email TEXT)")) {
                    statement.execute(sql);
                }
            }
            ready = true;
        }
        return List.of(
                "--import.source.url=" + LEGACY.getJdbcUrl(),
                "--import.source.user=" + LEGACY.getUsername(),
                "--import.source.password=" + LEGACY.getPassword());
    }
}
