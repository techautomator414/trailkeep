// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The SQL Server export recipe of docs/importer/export-recipes.md (WP-26 part ae3, design §4.2.2, §11; {@code
 * -Psqlserver-it}): the export and manifest scripts, read from the page itself, run unchanged with the image's own
 * {@code sqlcmd} as the source account of docs/importer/source-accounts.md, against a database snapshot the
 * administrator created of a legacy database with awkward values (commas, quotes and line breaks, NULL next to the
 * empty string, non-ASCII text, {@code DATETIME} and seven-digit {@code DATETIME2}, an integer key). The JSON Lines
 * they leave pass the manifest checks, and the file extract and the JDBC extract of the same snapshot, whose queries
 * are the script's own, stage every row with the same keyed row hash and the same issues (source equivalence). A
 * description over 1 MB settles whether {@code sqlcmd -y 0} cuts it (design §4.2.2). The licence of the Developer
 * edition image is accepted through its documented variable, for testing only. The legacy database, its snapshot and
 * the login are created before each test and dropped after it; the container stops with the JVM.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("sqlserver")
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
class SqlServerExportRecipeIT extends ExtractJobSupport {

    private static final DockerImageName IMAGE =
            DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-CU27-ubuntu-22.04");

    private static final String DETAILS = "First line\nSecond line, with a comma\n\"Quoted\" third";

    private static final String PASSWORD = "Tk" + HexFormat.of().formatHex(new SecureRandom().generateSeed(12));

    private static final MSSQLServerContainer LEGACY =
            new MSSQLServerContainer(IMAGE).acceptLicense().withPassword(PASSWORD);

    private static final String FILES = """
            version: 1
            source: { id: memory1, kind: files, timezone: UTC, manifest: manifest.yaml }
            limits: { maxErrorRate: 0.0, batchSize: 2 }
            lookups:
              status: { "Open": OPEN, "Closed": CLOSED }
            people: { file: people.jsonl, key: user_id, match: [email], unmatched: snapshot,
                      fields: { displayName: name, email: mail } }
            entities:
              case:
                file: cases.jsonl
                key: case_id
                fields: { title: subject, description: details, caseTypeKey: { const: general-review },
                          teamKey: { const: team-a }, status: { lookup: status, from: state }, openedAt: opened,
                          identifiedOn: found_on, ownerKey: owner }
              comment:
                file: notes.jsonl
                key: note_id
                parent: { entity: case, columns: [case_id] }
                fields: { authorKey: author, writtenAt: created, body: { text: body, format: plain } }
            """;

    @Override
    String vendorProfile() {
        return "test";
    }

    @BeforeEach
    void legacyDatabaseWithTheSourceAccount() throws SQLException {
        synchronized (SqlServerExportRecipeIT.class) {
            if (!LEGACY.isRunning()) {
                LEGACY.start();
            }
        }
        dropLegacyDatabase();
        admin("master", "CREATE DATABASE legacy", "CREATE LOGIN trailkeep_source WITH PASSWORD = '" + PASSWORD + "'");
        admin(
                "legacy",
                "CREATE TABLE dbo.people (user_id VARCHAR(10), name NVARCHAR(50), mail NVARCHAR(50))",
                "INSERT INTO dbo.people VALUES ('u1', N'Synthetic One', N'One@Example.TEST'),"
                        + " ('u2', N'Zoë Synthétique', NULL)",
                "CREATE TABLE dbo.cases (case_id INT, subject NVARCHAR(100), details NVARCHAR(MAX), state VARCHAR(10),"
                        + " opened DATETIME, found_on DATE, owner VARCHAR(10))",
                "INSERT INTO dbo.cases VALUES (101, N'Synthetic case ✓ 101', N'', 'Closed', '2019-03-31T01:30:00',"
                        + " '2019-03-30', 'u2'),"
                        + " (102, N'Synthetic case 102', NULL, 'Open', '2019-04-01T12:00:00.997', '2019-04-01', 'u1')",
                "CREATE TABLE dbo.notes (note_id VARCHAR(10), case_id INT, author VARCHAR(10), created DATETIME2,"
                        + " body NVARCHAR(MAX))",
                "INSERT INTO dbo.notes VALUES ('n1', 100, 'u2', '2019-03-02T10:00:00.1234567',"
                        + " N'Note line one' + CHAR(10) + N'line two'),"
                        + " ('n2', 101, 'u1', '2019-03-02T10:00:00', N'Note with \"quotes\", commas')",
                // The recipe's account, as docs/importer/source-accounts.md gives it.
                "CREATE USER trailkeep_source FOR LOGIN trailkeep_source",
                "GRANT SELECT ON dbo.people TO trailkeep_source",
                "GRANT SELECT ON dbo.cases TO trailkeep_source",
                "GRANT SELECT ON dbo.notes TO trailkeep_source");
        legacyCase(100, "Synthetic, \"quoted\" case", DETAILS);
    }

    @AfterEach
    void dropLegacyDatabase() throws SQLException {
        admin(
                "master",
                "IF DB_ID('legacy_export') IS NOT NULL DROP DATABASE legacy_export",
                "IF DB_ID('legacy') IS NOT NULL ALTER DATABASE legacy SET SINGLE_USER WITH ROLLBACK IMMEDIATE",
                "IF DB_ID('legacy') IS NOT NULL DROP DATABASE legacy",
                "IF SUSER_ID('trailkeep_source') IS NOT NULL DROP LOGIN trailkeep_source");
    }

    @Test
    void recipeFilesStageTheRowsTheJdbcSourceStagesRowForRow(@TempDir Path directory) throws Exception {
        snapshot();
        String script = ExportRecipes.block("sqlserver/export.sh");
        export(script, directory);
        assertThat(Files.readString(directory.resolve("manifest.yaml")))
                .as("the database snapshot is the fixed point")
                .containsPattern("fixedPoint: \\{ kind: snapshot, value: \"legacy_export@[0-9T:.-]+\" }")
                .contains("{ file: cases.jsonl, entity: case, rows: 3, sha256: ");

        Path filesMapping = mapping(directory, FILES);
        long files = validatedRun(filesMapping);
        extract(files, filesMapping, null).close();
        Path jdbcMapping = mapping(directory, jdbc(script));
        long live = validatedRun(jdbcMapping);
        extract(
                        live,
                        jdbcMapping,
                        null,
                        "--import.source.url=" + url("legacy_export"),
                        "--import.source.user=trailkeep_source",
                        "--import.source.password=" + PASSWORD)
                .close();

        assertThat(staged(files)).containsExactly(2, 3, 2, 0, 0);
        assertThat(staged(live)).containsExactly(2, 3, 2, 0, 0);
        for (String table : List.of("IMPORT_STG_PERSON", "IMPORT_STG_CASE", "IMPORT_STG_COMMENT")) {
            assertThat(ExportRecipes.rowHashes(jdbc, table, files))
                    .as(table)
                    .isEqualTo(ExportRecipes.rowHashes(jdbc, table, live));
        }
        assertThat(issues(files))
                .as("the same issues: none")
                .isEqualTo(issues(live))
                .isEmpty();
        assertThat(jdbc.queryForList(
                        "SELECT DESCRIPTION FROM IMPORT_STG_CASE WHERE RUN_ID = ? ORDER BY LEGACY_KEY",
                        String.class,
                        files))
                .as("line breaks, quotes, commas, the empty string and NULL arrive as they were")
                .containsExactly(DETAILS, "", null);
        String writtenAt = "SELECT WRITTEN_AT FROM IMPORT_STG_COMMENT WHERE RUN_ID = ? AND LEGACY_KEY = 'n1'";
        assertThat(jdbc.queryForObject(writtenAt, Timestamp.class, files))
                .as("seven-digit DATETIME2, kept to the staging column's microsecond")
                .isEqualTo(jdbc.queryForObject(writtenAt, Timestamp.class, live))
                .isIn(
                        Timestamp.from(Instant.parse("2019-03-02T10:00:00.123456Z")),
                        Timestamp.from(Instant.parse("2019-03-02T10:00:00.123457Z")));
    }

    @Test
    void descriptionOverOneMegabyteIsExportedWhole(@TempDir Path directory) throws Exception {
        String longText = "d".repeat(1_100_000);
        legacyCase(103, "Synthetic long case", longText);
        snapshot();
        export(ExportRecipes.block("sqlserver/export.sh"), directory);
        Path filesMapping = mapping(directory, FILES);
        long files = validatedRun(filesMapping);

        extract(files, filesMapping, null).close();

        assertThat(issues(files)).isEmpty();
        assertThat(jdbc.queryForList(
                        "SELECT DESCRIPTION FROM IMPORT_STG_CASE WHERE RUN_ID = ? AND LEGACY_KEY = '103'",
                        String.class,
                        files))
                .as("sqlcmd -y 0 did not cut it at 1 MB (design §4.2.2)")
                .containsExactly(longText);
    }

    /** As the administrator: the snapshot the export reads; the source account reads it with its own grants. */
    private static void snapshot() throws SQLException {
        admin(
                "master",
                "CREATE DATABASE legacy_export ON (NAME = legacy, FILENAME = '/var/opt/mssql/data/legacy_export.ss')"
                        + " AS SNAPSHOT OF legacy");
    }

    /**
     * Runs the export script with the container's {@code sqlcmd} as the source account, reading the snapshot, then
     * the manifest script, in a folder the server's OS user owns, and copies out the manifest and every file it lists.
     */
    private static void export(String script, Path directory) throws Exception {
        String folder = "/tmp/export-" + System.nanoTime();
        ExecResult made = LEGACY.execInContainer("mkdir", "-p", folder);
        assertThat(made.getExitCode()).as(made.getStderr()).isZero();
        LEGACY.copyFileToContainer(
                Transferable.of(script.getBytes(StandardCharsets.UTF_8), 0644), folder + "/export.sh");
        LEGACY.copyFileToContainer(
                Transferable.of(ExportRecipes.block("sqlserver/manifest.sh").getBytes(StandardCharsets.UTF_8), 0644),
                folder + "/manifest.sh");
        // The test container's certificate is self-signed: -C trusts it, as the script's comment allows a test to.
        ExecResult result = LEGACY.execInContainer(
                "sh",
                "-c",
                "cd " + folder + " && export PATH=$PATH:/opt/mssql-tools18/bin SQLCMDSERVER=localhost"
                        + " SQLCMDDBNAME=legacy_export SQLCMDUSER=trailkeep_source SQLCMDPASSWORD=" + PASSWORD
                        + " SQLCMD_TLS=-C && sh export.sh && sh manifest.sh");
        assertThat(result.getExitCode())
                .as(result.getStdout() + result.getStderr())
                .isZero();
        for (String file : List.of("manifest.yaml", "people.jsonl", "cases.jsonl", "notes.jsonl")) {
            LEGACY.copyFileFromContainer(
                    folder + "/" + file, directory.resolve(file).toString());
        }
    }

    /** The files mapping turned into the JDBC one: the same fields, each entity's query the script's own. */
    private static String jdbc(String script) {
        Map<String, String> queries = new LinkedHashMap<>();
        Matcher json =
                Pattern.compile("(?m)^json \"(SELECT .+)\" ([a-z]+\\.jsonl)$").matcher(script);
        while (json.find()) {
            queries.put(json.group(2), json.group(1));
        }
        assertThat(queries).containsOnlyKeys("people.jsonl", "cases.jsonl", "notes.jsonl");
        String mapping = FILES.replace(
                "kind: files, timezone: UTC, manifest: manifest.yaml",
                "kind: jdbc, vendor: sqlserver, url: \"${import.source.url}\", user: \"${import.source.user}\","
                        + " timezone: UTC");
        for (Map.Entry<String, String> query : queries.entrySet()) {
            mapping = mapping.replace("file: " + query.getKey(), "query: \"" + query.getValue() + "\"");
        }
        return mapping;
    }

    private static void legacyCase(int id, String subject, String details) throws SQLException {
        try (Connection admin = adminLogin("legacy");
                PreparedStatement insert = admin.prepareStatement("INSERT INTO dbo.cases VALUES (?, ?, ?, 'Open',"
                        + " '2019-03-01T09:30:00.250', '2019-02-28', 'u1')")) {
            insert.setInt(1, id);
            insert.setString(2, subject);
            insert.setString(3, details);
            insert.executeUpdate();
        }
    }

    /** The source account's URL; TLS is off only because the test container has no certificate to verify. */
    private static String url(String database) {
        return "jdbc:sqlserver://" + LEGACY.getHost() + ":" + LEGACY.getMappedPort(1433) + ";databaseName=" + database
                + ";encrypt=false";
    }

    private static Connection adminLogin(String database) throws SQLException {
        return DriverManager.getConnection(url(database), LEGACY.getUsername(), LEGACY.getPassword());
    }

    private static void admin(String database, String... statements) throws SQLException {
        try (Connection admin = adminLogin(database);
                Statement statement = admin.createStatement()) {
            for (String sql : statements) {
                // Test-only DDL and seed rows, fixed in this class.
                statement.execute(sql);
            }
        }
    }
}
