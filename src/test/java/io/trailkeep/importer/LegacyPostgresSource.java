// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A synthetic PostgreSQL legacy database holding the rows {@link MemorySource} holds (WP-26 part n2), read through the
 * source-account recipe of docs/importer/source-accounts.md: {@code SELECT} on the four mapped tables and nothing on
 * {@code legacy.secrets}. The one attachment's bytes are a {@code bytea} column, read by its content query. Started
 * once, on first use, and stopped with the JVM.
 */
final class LegacyPostgresSource {

    /** The attachment's bytes, in {@code legacy.files.content}. */
    static final byte[] SCAN = "synthetic scan from the legacy database".getBytes(StandardCharsets.UTF_8);

    private static final PostgreSQLContainer LEGACY =
            new PostgreSQLContainer(PostgresTestcontainersConfiguration.IMAGE);

    private static final String PASSWORD = "Tk" + HexFormat.of().formatHex(new SecureRandom().generateSeed(12));

    private static boolean ready;

    private LegacyPostgresSource() {}

    /**
     * The extract's mapping turned from the in-memory files to this database's queries.
     *
     * @param mapping {@link ExtractJobSupport#MAPPING}
     * @return the same mapping with a PostgreSQL source, entity queries and a content query
     */
    static String mapping(String mapping) {
        return mapping.replace(
                        "kind: files, timezone: UTC, manifest: manifest.yaml",
                        "kind: jdbc, vendor: postgresql, url: \"${import.source.url}\","
                                + " user: \"${import.source.user}\","
                                + " timezone: UTC")
                .replace("file: people.mem", "query: \"SELECT user_id, name, mail FROM legacy.people\"")
                .replace("file: cases.mem", "query: \"SELECT * FROM legacy.cases\"")
                .replace("file: notes.mem", "query: \"SELECT * FROM legacy.notes\"")
                .replace("file: files.mem", "query: \"SELECT file_id, case_id, name, bytes FROM legacy.files\"")
                .replace(
                        "content: { path: path, size: bytes }",
                        "content: { query: \"SELECT content FROM legacy.files WHERE file_id = ?\", size: bytes }");
    }

    /**
     * The {@code import.source.*} arguments, starting the database on first use.
     *
     * @return {@code --import.source.url}, {@code --import.source.user} and {@code --import.source.password}
     * @throws SQLException if the legacy schema cannot be created
     */
    static synchronized List<String> args() throws SQLException {
        if (!ready) {
            LEGACY.start();
            create();
            ready = true;
        }
        return List.of(
                "--import.source.url=" + LEGACY.getJdbcUrl(),
                "--import.source.user=trailkeep_source",
                "--import.source.password=" + PASSWORD);
    }

    private static void create() throws SQLException {
        try (Connection admin =
                        DriverManager.getConnection(LEGACY.getJdbcUrl(), LEGACY.getUsername(), LEGACY.getPassword());
                Statement statement = admin.createStatement()) {
            for (String sql : List.of(
                    "CREATE SCHEMA legacy",
                    "CREATE TABLE legacy.people (user_id TEXT, name TEXT, mail TEXT)",
                    "INSERT INTO legacy.people VALUES ('u1', 'Synthetic One', 'one@example.test'),"
                            + " ('u2', 'Synthetic Two', 'two@example.test')",
                    "CREATE TABLE legacy.cases (case_id TEXT, subject TEXT, details TEXT, state TEXT,"
                            + " opened TIMESTAMP, found_on DATE, owner TEXT)",
                    "INSERT INTO legacy.cases (case_id, subject, state) VALUES ('c1', 'Synthetic case one', 'Open'),"
                            + " ('c2', 'Synthetic case c2', 'Closed'), ('c3', 'Synthetic case c3', 'Weird')",
                    "UPDATE legacy.cases SET details = 'Synthetic details', opened = '2019-03-01 09:30',"
                            + " found_on = '2019-02-28', owner = 'u1'",
                    "CREATE TABLE legacy.notes (note_id TEXT, case_id TEXT, author TEXT, created TIMESTAMP, body TEXT)",
                    "INSERT INTO legacy.notes VALUES ('n1', 'c1', 'u2', '2019-03-02 10:00', 'Synthetic note'),"
                            + " ('n2', 'c1', 'u2', '2019-03-02 10:00', 'Synthetic note'),"
                            + " ('n2', 'c1', 'u2', '2019-03-02 10:00', 'Synthetic note')",
                    "CREATE TABLE legacy.files (file_id TEXT, case_id TEXT, name TEXT, bytes BIGINT, content BYTEA)",
                    "CREATE TABLE legacy.secrets (id INTEGER)",
                    // The recipe, as docs/importer/source-accounts.md gives it.
                    "CREATE ROLE trailkeep_source LOGIN PASSWORD '" + PASSWORD + "'",
                    "GRANT USAGE ON SCHEMA legacy TO trailkeep_source",
                    "GRANT SELECT ON legacy.people, legacy.cases, legacy.notes, legacy.files TO trailkeep_source")) {
                statement.execute(sql);
            }
            try (PreparedStatement file =
                    admin.prepareStatement("INSERT INTO legacy.files VALUES ('f1', 'c1', 'scan.pdf', 2048, ?)")) {
                file.setBytes(1, SCAN);
                file.executeUpdate();
            }
        }
    }
}
