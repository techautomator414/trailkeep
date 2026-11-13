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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The PostgreSQL export recipe of docs/importer/export-recipes.md (WP-26 part ae1, design §4.2.2, §11): the script and
 * the manifest script, read from the page itself, run unchanged with {@code psql} in a PostgreSQL container as the
 * source account of docs/importer/source-accounts.md, against a legacy database with awkward values (commas, quotes
 * and line breaks, NULL next to the empty string, non-ASCII text, fractional seconds, a {@code timestamptz}, an
 * integer key, {@code bytea} content). The files they leave pass the manifest checks, and the file extract and the
 * JDBC extract of the same database, whose queries are the script's own, stage every row with the same keyed row hash
 * and the same issues (source equivalence). The container stops with the JVM; each run is removed after the test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
class PostgresExportRecipeIT extends ExtractJobSupport {

    /** The page the recipe is read from, relative to the project, where Failsafe runs. */
    static final Path RECIPES = Path.of("docs/importer/export-recipes.md");

    private static final byte[] SCAN = "synthetic scan exported as bytea hex".getBytes(StandardCharsets.UTF_8);

    private static final String DETAILS = "First line\nSecond line, with a comma\n\"Quoted\" third";

    private static final String PASSWORD = "Tk" + HexFormat.of().formatHex(new SecureRandom().generateSeed(12));

    private static final PostgreSQLContainer LEGACY =
            new PostgreSQLContainer(PostgresTestcontainersConfiguration.IMAGE);

    private static final String FILES = """
            version: 1
            source: { id: memory1, kind: files, timezone: UTC, manifest: manifest.yaml, csv: { nullToken: "" } }
            limits: { maxErrorRate: 0.0, batchSize: 2 }
            lookups:
              status: { "Open": OPEN, "Closed": CLOSED }
            people: { file: people.csv, key: user_id, match: [email], unmatched: snapshot,
                      fields: { displayName: name, email: mail } }
            entities:
              case:
                file: cases.csv
                key: case_id
                fields: { title: subject, description: details, caseTypeKey: { const: general-review },
                          teamKey: { const: team-a }, status: { lookup: status, from: state }, openedAt: opened,
                          identifiedOn: found_on, ownerKey: owner }
              comment:
                file: notes.csv
                key: note_id
                parent: { entity: case, columns: [case_id] }
                fields: { authorKey: author, writtenAt: created, body: { text: body, format: plain } }
              attachment:
                file: files.csv
                key: file_id
                parent: { entity: case, columns: [case_id] }
                fields: { fileName: name, uploadedAt: uploaded, content: { binary: hex, column: content } }
            """;

    @Override
    String vendorProfile() {
        return "test";
    }

    @BeforeAll
    static void legacyDatabaseWithTheSourceAccount() throws SQLException {
        LEGACY.start();
        try (Connection admin =
                        DriverManager.getConnection(LEGACY.getJdbcUrl(), LEGACY.getUsername(), LEGACY.getPassword());
                Statement statement = admin.createStatement()) {
            for (String sql : List.of(
                    "CREATE SCHEMA legacy",
                    "CREATE TABLE legacy.people (user_id TEXT, name TEXT, mail TEXT)",
                    "INSERT INTO legacy.people VALUES ('u1', 'Synthetic One', 'One@Example.TEST'),"
                            + " ('u2', 'Zoë Synthétique', NULL)",
                    "CREATE TABLE legacy.cases (case_id INTEGER, subject TEXT, details TEXT, state TEXT,"
                            + " opened TIMESTAMP, found_on DATE, owner TEXT)",
                    "INSERT INTO legacy.cases VALUES"
                            + " (100, 'Synthetic, \"quoted\" case', NULL, 'Open', '2019-03-01 09:30:00.25',"
                            + " '2019-02-28', 'u1'),"
                            + " (101, 'Synthetic case ✓ 101', '', 'Closed', '2019-03-31 01:30:00', '2019-03-30', 'u2'),"
                            + " (102, 'Synthetic case 102', NULL, 'Open', '2019-04-01 12:00:00.123456', '2019-04-01',"
                            + " 'u1')",
                    "CREATE TABLE legacy.notes (note_id TEXT, case_id INTEGER, author TEXT, created TIMESTAMP,"
                            + " body TEXT)",
                    "INSERT INTO legacy.notes VALUES"
                            + " ('n1', 100, 'u2', '2019-03-02 10:00:00', E'Note line one\\nline two'),"
                            + " ('n2', 101, 'u1', '2019-03-02 10:00:00.5', 'Note with \"quotes\", commas')",
                    "CREATE TABLE legacy.files (file_id TEXT, case_id INTEGER, name TEXT, bytes BIGINT,"
                            + " uploaded TIMESTAMPTZ, content BYTEA)",
                    // The recipe's account, as docs/importer/source-accounts.md gives it.
                    "CREATE ROLE trailkeep_source LOGIN PASSWORD '" + PASSWORD + "'",
                    "GRANT USAGE ON SCHEMA legacy TO trailkeep_source",
                    "GRANT SELECT ON legacy.people, legacy.cases, legacy.notes, legacy.files TO trailkeep_source")) {
                statement.execute(sql);
            }
            try (PreparedStatement details =
                    admin.prepareStatement("UPDATE legacy.cases SET details = ? WHERE case_id = 100")) {
                details.setString(1, DETAILS);
                details.executeUpdate();
            }
            try (PreparedStatement file =
                    admin.prepareStatement("INSERT INTO legacy.files VALUES ('f1', 100, 'scan.pdf', ?, ?, ?)")) {
                file.setLong(1, SCAN.length);
                file.setTimestamp(2, Timestamp.from(Instant.parse("2019-03-03T08:15:00Z")));
                file.setBytes(3, SCAN);
                file.executeUpdate();
            }
        }
    }

    @Test
    void recipeFilesStageTheRowsTheJdbcSourceStagesRowForRow(@TempDir Path directory) throws Exception {
        String script = recipe("postgresql/export.sql");
        export(script, recipe("postgresql/manifest.sh"), directory);
        assertThat(Files.readString(directory.resolve("manifest.yaml")))
                .as("the export's own snapshot is its fixed point")
                .contains("fixedPoint: { kind: snapshot, value: \"")
                .contains("{ file: cases.csv, entity: case, rows: 3, sha256: ");

        Path filesMapping = mapping(directory, FILES);
        long files = validatedRun(filesMapping);
        extract(files, filesMapping, null, "--import.content-area=" + area(directory, "files"))
                .close();
        Path jdbcMapping = mapping(directory, jdbc(script));
        long live = validatedRun(jdbcMapping);
        List<String> args = new ArrayList<>(List.of(
                "--import.source.url=" + LEGACY.getJdbcUrl(),
                "--import.source.user=trailkeep_source",
                "--import.source.password=" + PASSWORD,
                "--import.content-area=" + area(directory, "jdbc")));
        extract(live, jdbcMapping, null, args.toArray(String[]::new)).close();

        assertThat(staged(files)).containsExactly(2, 3, 2, 1, 0);
        assertThat(staged(live)).containsExactly(2, 3, 2, 1, 0);
        for (String table :
                List.of("IMPORT_STG_PERSON", "IMPORT_STG_CASE", "IMPORT_STG_COMMENT", "IMPORT_STG_ATTACHMENT")) {
            assertThat(rowHashes(table, files)).as(table).isEqualTo(rowHashes(table, live));
        }
        assertThat(issues(files))
                .as("the same issues: none")
                .isEqualTo(issues(live))
                .isEmpty();
        assertThat(jdbc.queryForList(
                        "SELECT DESCRIPTION FROM IMPORT_STG_CASE WHERE RUN_ID = ? AND LEGACY_KEY = '100'",
                        String.class,
                        files))
                .as("commas, quotes and line breaks arrive intact")
                .containsExactly(DETAILS);
        assertThat(jdbc.queryForList(
                        "SELECT DESCRIPTION FROM IMPORT_STG_CASE WHERE RUN_ID = ? AND LEGACY_KEY IN ('101', '102')"
                                + " ORDER BY LEGACY_KEY",
                        String.class,
                        files))
                .as("the empty string and NULL stay apart")
                .containsExactly("", null);
        assertThat(jdbc.queryForObject(
                        "SELECT OPENED_AT FROM IMPORT_STG_CASE WHERE RUN_ID = ? AND LEGACY_KEY = '100'",
                        Timestamp.class,
                        files))
                .isEqualTo(Timestamp.from(Instant.parse("2019-03-01T09:30:00.25Z")));
        String sha256 = ValidateMappingCommand.sha256(SCAN);
        assertThat(jdbc.queryForObject(
                        "SELECT SHA256 FROM IMPORT_STG_ATTACHMENT WHERE RUN_ID = ?", String.class, files))
                .as("the bytea content, decoded from its \\x hex")
                .isEqualTo(sha256);
        assertThat(directory.resolve("files").resolve(Long.toString(files)).resolve(sha256))
                .hasBinaryContent(SCAN);
    }

    /**
     * Runs the script and then the manifest script in one folder of the container, as the source account, and copies
     * out the manifest and every file it lists.
     */
    private static void export(String script, String manifest, Path directory) throws Exception {
        String folder = "/tmp/export-" + System.nanoTime();
        LEGACY.copyFileToContainer(Transferable.of(script.getBytes(StandardCharsets.UTF_8)), folder + "/export.sql");
        LEGACY.copyFileToContainer(Transferable.of(manifest.getBytes(StandardCharsets.UTF_8)), folder + "/manifest.sh");
        ExecResult result = LEGACY.execInContainer(
                "sh",
                "-c",
                "cd " + folder + " && PGPASSWORD=" + PASSWORD + " psql -X -v ON_ERROR_STOP=1"
                        + " 'host=localhost dbname=" + LEGACY.getDatabaseName() + " user=trailkeep_source'"
                        + " -f export.sql && sh manifest.sh");
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        assertThat(result.getStderr()).isEmpty();
        for (String file : List.of("manifest.yaml", "people.csv", "cases.csv", "notes.csv", "files.csv")) {
            LEGACY.copyFileFromContainer(
                    folder + "/" + file, directory.resolve(file).toString());
        }
    }

    /** The files mapping turned into the JDBC one: the same fields, each entity's query the script's own. */
    private static String jdbc(String script) {
        Map<String, String> queries = new LinkedHashMap<>();
        Matcher copy = Pattern.compile("(?m)^\\\\copy \\((SELECT .+? FROM legacy\\.[a-z]+)\\) TO '([a-z]+\\.csv)'")
                .matcher(script);
        while (copy.find()) {
            queries.put(copy.group(2), copy.group(1));
        }
        assertThat(queries).containsOnlyKeys("people.csv", "cases.csv", "notes.csv", "files.csv");
        String mapping = FILES.replace(
                        "kind: files, timezone: UTC, manifest: manifest.yaml, csv: { nullToken: \"\" }",
                        "kind: jdbc, vendor: postgresql, url: \"${import.source.url}\","
                                + " user: \"${import.source.user}\", timezone: UTC")
                .replace(
                        "content: { binary: hex, column: content }",
                        "content: { query: \"SELECT content FROM legacy.files WHERE file_id = ?\", size: bytes }");
        for (Map.Entry<String, String> query : queries.entrySet()) {
            mapping = mapping.replace("file: " + query.getKey(), "query: \"" + query.getValue() + "\"");
        }
        return mapping;
    }

    /** A command block of the recipe page: the fenced block under the {@code <!-- recipe: name ... -->} marker. */
    static String recipe(String name) throws Exception {
        String page = Files.readString(RECIPES);
        int marker = page.indexOf("<!-- recipe: " + name + " ");
        assertThat(marker).as("recipe " + name + " on " + RECIPES).isNotNegative();
        int open = page.indexOf('\n', page.indexOf("```", marker)) + 1;
        return page.substring(open, page.indexOf("```", open));
    }

    private static Path area(Path directory, String name) throws Exception {
        return Files.createDirectories(directory.resolve(name));
    }

    /** A run's staged rows of one table: each key with its keyed row hash. */
    private Map<String, String> rowHashes(String table, long run) {
        Map<String, String> hashes = new LinkedHashMap<>();
        // Test-only read over the fixed table names the test passes.
        jdbc.query(
                "SELECT LEGACY_KEY, ROW_HASH FROM " + table + " WHERE RUN_ID = ? ORDER BY LEGACY_KEY",
                (row, index) -> hashes.put(row.getString(1), row.getString(2)),
                run);
        return hashes;
    }
}
