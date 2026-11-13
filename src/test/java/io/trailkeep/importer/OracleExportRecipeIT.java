// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.images.builder.Transferable;

/**
 * The Oracle export recipe of docs/importer/export-recipes.md (WP-26 part ae2, design §4.2.2, §11; {@code
 * -Poracle-it}, both images): the SQL*Plus script and the manifest script, read from the page itself, run unchanged
 * with the image's own SQL*Plus as the source account of docs/importer/source-accounts.md, against a legacy schema with
 * awkward values (CLOBs with commas, quotes and line breaks, non-ASCII text, a {@code DATE} and a fractional {@code
 * TIMESTAMP}, an integer key). The JSON Lines they leave pass the manifest checks, and the file extract and the JDBC
 * extract of the same schema, whose queries are the script's own, stage every row with the same keyed row hash and the
 * same issues (source equivalence). A row longer than SQL*Plus can write makes the file extract refuse the file. The
 * legacy users are created before each test and dropped after it.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleExportRecipeIT extends ExtractJobSupport {

    private static final String DETAILS = "First line\nSecond line, with a comma\n\"Quoted\" third";

    private static final String PASSWORD = "Tk" + HexFormat.of().formatHex(new SecureRandom().generateSeed(12));

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

    @Autowired
    private JdbcDatabaseContainer<?> oracle;

    @Autowired
    private JdbcConnectionDetails connection;

    @Override
    String vendorProfile() {
        return "oracle-test";
    }

    @BeforeEach
    void legacySchemaWithTheSourceAccount() throws SQLException {
        dropLegacyUsers();
        admin(
                "CREATE USER legacy IDENTIFIED BY \"" + PASSWORD + "\" QUOTA UNLIMITED ON USERS",
                "CREATE TABLE legacy.people (user_id VARCHAR2(10), name VARCHAR2(50), mail VARCHAR2(50))",
                "INSERT INTO legacy.people VALUES ('u1', 'Synthetic One', 'One@Example.TEST')",
                "INSERT INTO legacy.people VALUES ('u2', 'Zoë Synthétique', NULL)",
                "CREATE TABLE legacy.cases (case_id NUMBER(10), subject VARCHAR2(100), details CLOB,"
                        + " state VARCHAR2(10), opened DATE, found_on DATE, owner VARCHAR2(10))",
                "INSERT INTO legacy.cases VALUES (101, 'Synthetic case ✓ 101', NULL, 'Closed',"
                        + " TIMESTAMP '2019-03-31 01:30:00', DATE '2019-03-30', 'u2')",
                "CREATE TABLE legacy.notes (note_id VARCHAR2(10), case_id NUMBER(10), author VARCHAR2(10),"
                        + " created TIMESTAMP, body CLOB)",
                "INSERT INTO legacy.notes VALUES ('n1', 100, 'u2', TIMESTAMP '2019-03-02 10:00:00.25',"
                        + " TO_CLOB('Note line one' || CHR(10) || 'line two'))",
                "INSERT INTO legacy.notes VALUES ('n2', 101, 'u1', TIMESTAMP '2019-03-02 10:00:00',"
                        + " TO_CLOB('Note with \"quotes\", commas'))",
                // The recipe's account, as docs/importer/source-accounts.md gives it.
                "CREATE USER trailkeep_source IDENTIFIED BY \"" + PASSWORD + "\"",
                "GRANT CREATE SESSION TO trailkeep_source",
                "GRANT READ ON legacy.people TO trailkeep_source",
                "GRANT READ ON legacy.cases TO trailkeep_source",
                "GRANT READ ON legacy.notes TO trailkeep_source");
        legacyCase(100, "Synthetic, \"quoted\" case", DETAILS);
    }

    @AfterEach
    void dropLegacyUsers() throws SQLException {
        for (String drop : List.of("DROP USER trailkeep_source CASCADE", "DROP USER legacy CASCADE")) {
            try (Connection admin = systemLogin();
                    Statement statement = admin.createStatement()) {
                // Test-only DDL, fixed in this list.
                statement.execute(drop);
            } catch (SQLException missing) {
                // ORA-01918: not created yet.
            }
        }
    }

    @Test
    void recipeFilesStageTheRowsTheJdbcSourceStagesRowForRow(@TempDir Path directory) throws Exception {
        String script = ExportRecipes.block("oracle/export.sql");
        export(script, directory);
        assertThat(Files.readString(directory.resolve("manifest.yaml")))
                .as("the read-only transaction's SCN is the fixed point")
                .containsPattern("fixedPoint: \\{ kind: scn, value: \"[0-9]+\" }")
                .contains("{ file: cases.jsonl, entity: case, rows: 2, sha256: ");

        Path filesMapping = mapping(directory, FILES);
        long files = validatedRun(filesMapping);
        extract(files, filesMapping, null).close();
        Path jdbcMapping = mapping(directory, jdbc(script));
        long live = validatedRun(jdbcMapping);
        extract(
                        live,
                        jdbcMapping,
                        null,
                        "--import.source.url=" + connection.getJdbcUrl(),
                        "--import.source.user=trailkeep_source",
                        "--import.source.password=" + PASSWORD)
                .close();

        assertThat(staged(files)).containsExactly(2, 2, 2, 0, 0);
        assertThat(staged(live)).containsExactly(2, 2, 2, 0, 0);
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
                        "SELECT DESCRIPTION FROM IMPORT_STG_CASE WHERE RUN_ID = ? AND LEGACY_KEY = '100'",
                        String.class,
                        files))
                .as("commas, quotes and line breaks arrive intact")
                .containsExactly(DETAILS);
        assertThat(jdbc.queryForObject(
                        "SELECT WRITTEN_AT FROM IMPORT_STG_COMMENT WHERE RUN_ID = ? AND LEGACY_KEY = 'n1'",
                        Timestamp.class,
                        files))
                .isEqualTo(Timestamp.from(Instant.parse("2019-03-02T10:00:00.25Z")));
    }

    @Test
    void rowLongerThanSqlPlusCanWriteMakesTheFileExtractRefuseTheFile(@TempDir Path directory) throws Exception {
        legacyCase(102, "Synthetic long case", "d".repeat(40_000));
        export(ExportRecipes.block("oracle/export.sql"), directory);
        Path filesMapping = mapping(directory, FILES);
        long files = validatedRun(filesMapping);

        assertThatThrownBy(() -> extract(files, filesMapping, null))
                .as("SQL*Plus broke the row's JSON across lines")
                .hasMessage("Import run " + files + " cannot be extracted: ROW_UNREADABLE");
        assertThat(issues(files))
                .extracting(issue -> issue.get(0) + " " + issue.get(2))
                .contains("CASE ROW_UNREADABLE");
        assertThat(jdbc.queryForObject("SELECT STATUS FROM IMPORT_RUN WHERE ID = ?", String.class, files))
                .as("not extracted: the case entity is not checkpointed, so nothing of it is loadable")
                .isEqualTo("VALIDATED");
    }

    /**
     * Runs the script with the container's SQL*Plus as the source account, then the manifest script, in a folder the
     * database's OS user owns, and copies out the manifest and every file it lists.
     */
    private void export(String script, Path directory) throws Exception {
        String folder = "/tmp/export-" + System.nanoTime();
        ExecResult made = oracle.execInContainer("mkdir", "-p", folder);
        assertThat(made.getExitCode()).as(made.getStderr()).isZero();
        oracle.copyFileToContainer(
                Transferable.of(script.getBytes(StandardCharsets.UTF_8), 0644), folder + "/export.sql");
        oracle.copyFileToContainer(
                Transferable.of(ExportRecipes.block("oracle/manifest.sh").getBytes(StandardCharsets.UTF_8), 0644),
                folder + "/manifest.sh");
        ExecResult result = oracle.execInContainer(
                "sh",
                "-c",
                "cd " + folder + " && NLS_LANG=AMERICAN_AMERICA.AL32UTF8 sqlplus -S -L trailkeep_source/" + PASSWORD
                        + "@//localhost:1521/" + oracle.getDatabaseName() + " @export.sql && sh manifest.sh");
        assertThat(result.getExitCode())
                .as(result.getStdout() + result.getStderr())
                .isZero();
        for (String file : List.of("manifest.yaml", "people.jsonl", "cases.jsonl", "notes.jsonl")) {
            oracle.copyFileFromContainer(
                    folder + "/" + file, directory.resolve(file).toString());
        }
    }

    /** The files mapping turned into the JDBC one: the same fields, each entity's query the script's own. */
    private static String jdbc(String script) {
        Map<String, String> queries = new LinkedHashMap<>();
        Matcher spool = Pattern.compile("(?m)^SPOOL ([a-z]+\\.jsonl)\\n"
                        + "SELECT JSON_OBJECT\\(\\* RETURNING CLOB\\) FROM \\((SELECT .+)\\);$")
                .matcher(script);
        while (spool.find()) {
            queries.put(spool.group(1), spool.group(2));
        }
        assertThat(queries).containsOnlyKeys("people.jsonl", "cases.jsonl", "notes.jsonl");
        String mapping = FILES.replace(
                "kind: files, timezone: UTC, manifest: manifest.yaml",
                "kind: jdbc, vendor: oracle, url: \"${import.source.url}\", user: \"${import.source.user}\","
                        + " timezone: UTC");
        for (Map.Entry<String, String> query : queries.entrySet()) {
            mapping = mapping.replace("file: " + query.getKey(), "query: \"" + query.getValue() + "\"");
        }
        return mapping;
    }

    private void legacyCase(int id, String subject, String details) throws SQLException {
        try (Connection admin = systemLogin();
                PreparedStatement insert = admin.prepareStatement("INSERT INTO legacy.cases VALUES (?, ?, ?, 'Open',"
                        + " TIMESTAMP '2019-03-01 09:30:00', DATE '2019-02-28', 'u1')")) {
            insert.setInt(1, id);
            insert.setString(2, subject);
            insert.setString(3, details);
            insert.executeUpdate();
        }
        awaitReadOnlyReads();
    }

    /**
     * A read-only transaction cannot read a table created moments before it (ORA-01466), which only a test that
     * creates its legacy schema just before exporting it meets. Waits, at most 30 s, until one can.
     */
    private void awaitReadOnlyReads() throws SQLException {
        for (int attempt = 0; ; attempt++) {
            try (Connection account =
                            DriverManager.getConnection(connection.getJdbcUrl(), "trailkeep_source", PASSWORD);
                    Statement statement = account.createStatement()) {
                account.setAutoCommit(false);
                statement.execute("SET TRANSACTION READ ONLY");
                for (String table : List.of("people", "cases", "notes")) {
                    // Test-only probe over the fixed names of this list.
                    statement
                            .executeQuery("SELECT COUNT(*) FROM legacy." + table)
                            .close();
                }
                account.rollback();
                return;
            } catch (SQLException e) {
                if (e.getErrorCode() != 1466 || attempt >= 60) {
                    throw e;
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
        }
    }

    private void admin(String... statements) throws SQLException {
        try (Connection admin = systemLogin();
                Statement statement = admin.createStatement()) {
            for (String sql : statements) {
                // Test-only DDL and seed data, fixed in this class.
                statement.execute(sql);
            }
        }
    }

    private Connection systemLogin() throws SQLException {
        return DriverManager.getConnection(connection.getJdbcUrl(), "system", connection.getPassword());
    }
}
