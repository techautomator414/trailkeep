// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.importer.ImportRunService.RunDetail;
import io.trailkeep.shared.csv.CsvFormat;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.user.ImportPeopleService;
import io.trailkeep.user.UserDirectoryService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The worked example of docs/importer/worked-example.md, end to end (WP-26 part ae4, design §3, §11 "End to end"):
 * the page's legacy database, created by its own SQL block in a PostgreSQL container and read through the source
 * account of docs/importer/source-accounts.md; the example mapping with the page's two lookup files; and the Trailkeep
 * team, case type and users the page names. A Compliance Admin requests the run through the Import runs service; then
 * {@code validate-mapping}, {@code extract} and {@code dry-run} run as the import user, as the Job runs them, with the
 * figures the page shows; the sign-off acknowledges the page's two WARNING classes; {@code load} writes what the page
 * lists, held cases with their fixed reasons, and a second load changes nothing; {@code reconcile} finds no difference.
 * The legacy container stops with the JVM; each test removes what it wrote.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
abstract class AbstractWorkedExampleIT extends LoadSupport {

    /** The page, relative to the project, where Failsafe runs. */
    static final Path PAGE = Path.of("docs/importer/worked-example.md");

    private static final String EXTERNAL = "--app.attachments.scanning=external";

    private static final String LEGACY_CASES = "SELECT ID FROM CASE_RECORD WHERE LEGACY_REF LIKE 'legacy1:%'";

    private static final String PASSWORD = "Tk" + HexFormat.of().formatHex(new SecureRandom().generateSeed(12));

    private static final PostgreSQLContainer LEGACY =
            new PostgreSQLContainer(PostgresTestcontainersConfiguration.IMAGE);

    @Autowired
    private ImportRunService imports;

    @Autowired
    private UserDirectoryService directory;

    private long admin;

    private final List<Long> requested = new ArrayList<>();

    @BeforeAll
    static void theLegacyDatabaseOfThePage() throws Exception {
        if (LEGACY.isRunning()) {
            return;
        }
        LEGACY.start();
        try (Connection owner =
                        DriverManager.getConnection(LEGACY.getJdbcUrl(), LEGACY.getUsername(), LEGACY.getPassword());
                Statement statement = owner.createStatement()) {
            statement.execute(ExportRecipes.block(PAGE, "example/legacy.sql"));
            // The source account of docs/importer/source-accounts.md, with a password random per run.
            for (String sql : List.of(
                    "CREATE ROLE trailkeep_source LOGIN PASSWORD '" + PASSWORD + "'",
                    "GRANT USAGE ON SCHEMA dbo TO trailkeep_source",
                    "GRANT SELECT ON dbo.users, dbo.cases, dbo.notes, dbo.files, dbo.case_audit TO trailkeep_source",
                    "REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA dbo FROM PUBLIC")) {
                statement.execute(sql);
            }
        }
    }

    @Override
    String sourceId() {
        return "legacy1";
    }

    /** The Trailkeep side the page names: its team, case type and outcomes, two users and a Compliance Admin. */
    @BeforeEach
    void theTrailkeepSideOfThePage() {
        jdbc.update("UPDATE TEAM SET NAME = 'Intake review' WHERE ID = ?", team);
        jdbc.update("UPDATE CASE_TYPE SET NAME = 'general-review' WHERE ID = ?", caseType);
        jdbc.update(
                "INSERT INTO CASE_TYPE_OUTCOME (CASE_TYPE_ID, OUTCOME, SORT_ORDER) VALUES (?, 'UNSUBSTANTIATED', 2)",
                caseType);
        jdbc.update("UPDATE APP_USER SET EMAIL = 'analyst.one@example.test' WHERE ID = ?", analyst);
        jdbc.update("UPDATE APP_USER SET EMAIL = 'manager.two@example.test' WHERE ID = ?", manager);
        admin = new CaseSeed(jdbc).user();
        users.add(admin);
        jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, ?)", admin, "COMPLIANCE_ADMIN");
    }

    /**
     * Removes the requested run before the users who requested and approved it: what the load wrote that names the
     * run, the run with its staging, its audit rows and the role. The cases and users go after this, as for every
     * load test.
     */
    @AfterEach
    void removeTheRequestedRun() {
        for (long run : requested) {
            for (String table : List.of("CASE_IMPORTED_EVENT", "IMPORT_OWNER_PENDING")) {
                // Test-only cleanup over the fixed table names of this list.
                jdbc.update("DELETE FROM " + table + " WHERE RUN_ID = ?", run);
            }
            jdbc.update(
                    "DELETE FROM IMPORT_LEDGER_PART WHERE LEDGER_ID IN"
                            + " (SELECT ID FROM IMPORT_LEDGER WHERE RUN_ID = ?)",
                    run);
            jdbc.update("DELETE FROM IMPORT_LEDGER WHERE RUN_ID = ?", run);
            jdbc.update("DELETE FROM AUDIT_EVENT WHERE ENTITY_TYPE = 'IMPORT_RUN' AND ENTITY_ID = ?", run);
        }
        removeTheSeededRuns();
        requested.clear();
        jdbc.update("DELETE FROM APP_USER_GLOBAL_ROLE WHERE USER_ID = ?", admin);
    }

    @Test
    void theExampleRunsFromRequestToReconciledWithItsHeldCases(@TempDir Path temp) throws Exception {
        Path folder = folderOfThePage(temp.toRealPath());
        final Path mapping = folder.resolve("mapping.yaml");
        final String area = "--import.content-area=" + Files.createDirectories(folder.resolve("content"));
        Actor compliance = new Actor(directory.permissionsOf(admin), "Synthetic Compliance Admin");

        long run = imports.request(compliance, "legacy1", false);
        requested.add(run);
        removeAfter(run);
        assertThat(status(run)).isEqualTo("REQUESTED");

        command("validate-mapping", run, mapping, source()).close();
        assertThat(status(run)).isEqualTo("VALIDATED");

        command("extract", run, mapping, source(area)).close();
        assertThat(status(run)).isEqualTo("EXTRACTED");
        assertThat(staged(run))
                .as("people, cases, comments, attachments, events")
                .containsExactly(2, 5, 2, 1, 2);
        assertThat(issues(run)).containsExactly(List.of("CASE", "WARNING", "DST_AMBIGUOUS", hash("1002")));

        dryRun(run, mapping, EXTERNAL, area).close();
        assertThat(status(run)).isEqualTo("DRY_RUN");
        assertThat(dryRunIssues(run)).containsExactly(List.of("ATTACHMENT", "WARNING", "NOT_SCANNED", ""));
        assertThat(figuresAsThePageShowsThem(run)).isEqualTo(lines(ExportRecipes.block(PAGE, "example/figures.txt")));
        assertThat(folder.resolve(run + "/dry-run.csv")).exists();

        signOff(compliance, run);

        load(run, mapping, EXTERNAL, area).close();
        assertThat(status(run)).isEqualTo("LOADED");
        assertThat(loadIssues(run)).isEmpty();
        List<String> written = written(run);
        assertThat(written)
                .as("two closures and two holds, two alerts pre-marked per open case, one owner left to relink")
                .contains("CLOSURE_REQUEST 2", "LEGAL_HOLD_EVENT 2", "ALERT_LEDGER 6", "IMPORT_OWNER_PENDING 1");
        load(run, mapping, EXTERNAL, area).close();
        assertThat(written(run)).as("a second load changes nothing").isEqualTo(written);

        command("reconcile", run, mapping, "--import.reports-dir=" + folder).close();
        assertThat(status(run)).isEqualTo("RECONCILED");
        assertThat(count("SELECT COUNT(*) FROM IMPORT_ISSUE WHERE RUN_ID = ? AND STAGE = 'RECONCILE'", run))
                .isZero();
        assertThat(lines(
                        Files.readString(folder.resolve(run + "/reconcile.csv")).replace(CsvFormat.BOM, "")))
                .isEqualTo(lines(ExportRecipes.block(PAGE, "example/reconcile.csv")));
        assertThat(casesAsThePageListsThem(run)).isEqualTo(lines(ExportRecipes.block(PAGE, "example/cases.txt")));
        theChildRowsArriveAsThePageSays();

        command("purge-staging", run, mapping, area).close();
        assertThat(staged(run)).containsExactly(0, 0, 0, 0, 0);
        assertThat(folder.resolve("content/" + run)).doesNotExist();
        assertThat(folder.resolve(run + "/reconcile.csv"))
                .as("the reports stay")
                .exists();
    }

    /**
     * The folder the Job's claims give it, as the page lays it out: the mapping, as the page shows it, its two lookup
     * files from the page, and the attachment root.
     */
    private static Path folderOfThePage(Path folder) throws Exception {
        String example = new ClassPathResource("importer/example-mapping.yaml")
                .getContentAsString(StandardCharsets.UTF_8)
                .replace("vendor: sqlserver", "vendor: postgresql");
        assertThat(ExportRecipes.block(PAGE, "example/mapping.yaml"))
                .as("the page shows the example mapping, read from PostgreSQL")
                .isEqualTo(example.substring(example.indexOf("version: 1")));
        Path files = Files.createDirectories(folder.resolve("files"));
        Files.writeString(
                folder.resolve("mapping.yaml"),
                example.replace("root: /var/lib/trailkeep/import/files", "root: \"" + files + "\""));
        Path lookups = Files.createDirectories(folder.resolve("lookups"));
        Files.writeString(lookups.resolve("teams.csv"), ExportRecipes.block(PAGE, "example/teams.csv"));
        Files.writeString(
                lookups.resolve("default-owners.csv"), ExportRecipes.block(PAGE, "example/default-owners.csv"));
        return folder;
    }

    /** The sign-off as the Import runs screen makes it: the run as the page showed it, both warnings acknowledged. */
    private void signOff(Actor compliance, long run) {
        RunDetail detail = imports.run(compliance, run);
        assertThat(detail.signable()).isTrue();
        assertThat(detail.scanMode()).isEqualTo("EXTERNAL");
        assertThat(detail.warnings()).containsExactly("DST_AMBIGUOUS", "NOT_SCANNED");

        imports.signOff(compliance, run, detail.version(), Set.copyOf(detail.warnings()));

        assertThat(jdbc.queryForMap("SELECT APPROVED_BY, ACKNOWLEDGED_WARNINGS FROM IMPORT_RUN WHERE ID = ?", run))
                .containsEntry("ACKNOWLEDGED_WARNINGS", "DST_AMBIGUOUS,NOT_SCANNED")
                .satisfies(row -> assertThat(number(row, "APPROVED_BY")).isEqualTo(admin));
    }

    /** The dry run's figures as the page shows them: the team named, the forecast day left out, in sorted order. */
    private List<String> figuresAsThePageShowsThem(long run) {
        return figures(run).stream()
                .filter(figure -> !figure.startsWith("SLA_FORECAST_DAY "))
                .map(figure -> figure.replace(" " + team + " ", " team "))
                .sorted()
                .toList();
    }

    /** The loaded cases, one line each, as the page lists them: users by their part in the example, the run as N. */
    private List<String> casesAsThePageListsThem(long run) {
        Map<Long, String> who =
                Map.of(analyst, "analyst", manager, "manager", ImportPeopleService.IMPORTER_ID, "import");
        return jdbc.query(
                "SELECT LEGACY_NUMBER, STATUS, SENSITIVITY, OWNER_ID, OWNER_NAME_SNAPSHOT, OPENED_AT, IDENTIFIED_ON,"
                        + " LEGAL_HOLD_REASON FROM CASE_RECORD WHERE LEGACY_REF LIKE 'legacy1:%' ORDER BY LEGACY_REF",
                (row, index) -> String.join(
                        " ",
                        row.getString(1),
                        row.getString(2),
                        row.getString(3),
                        who.getOrDefault(row.getLong(4), "?"),
                        "snapshot=" + Objects.requireNonNullElse(row.getString(5), "-"),
                        "opened=" + row.getTimestamp(6).toInstant(),
                        "identified=" + row.getDate(7),
                        "hold="
                                + Objects.requireNonNullElse(row.getString(8), "-")
                                        .replace("(import run " + run + ")", "(import run N)")));
    }

    /**
     * LT-1001: its note escaped, its scan stored not scanned, its two history rows with the details redacted; LT-1003:
     * its note by the unmatched officer, whose name is kept.
     */
    private void theChildRowsArriveAsThePageSays() {
        assertThat(jdbc.queryForList(
                        "SELECT AUTHOR_NAME_SNAPSHOT FROM CASE_COMMENT WHERE CASE_ID IN"
                                + " (SELECT ID FROM CASE_RECORD WHERE LEGACY_NUMBER = 'LT-1003')",
                        String.class))
                .containsExactly("Synthetic Former Officer");
        long id = number(jdbc.queryForMap("SELECT ID FROM CASE_RECORD WHERE LEGACY_NUMBER = 'LT-1001'"), "ID");
        assertThat(jdbc.queryForList("SELECT BODY FROM CASE_COMMENT WHERE CASE_ID = ?", String.class, id))
                .containsExactly("Asked the synthetic vendor for the \\*original\\* invoice.");
        assertThat(jdbc.queryForMap(
                        "SELECT FILE_NAME, SIZE_BYTES, SCAN_STATUS FROM CASE_ATTACHMENT WHERE CASE_ID = ?", id))
                .containsEntry("FILE_NAME", "invoice-scan.txt")
                .containsEntry("SCAN_STATUS", "NOT_SCANNED")
                .satisfies(row -> assertThat(number(row, "SIZE_BYTES")).isEqualTo(34));
        assertThat(jdbc.query(
                        "SELECT FIELD_NAME, OLD_VALUE, NEW_VALUE, OLD_HMAC FROM CASE_IMPORTED_EVENT WHERE CASE_ID = ?"
                                + " ORDER BY AT_TS",
                        (row, index) -> row.getString(1) + " " + row.getString(2) + " " + row.getString(3) + " "
                                + (row.getString(4) == null ? "plain" : "redacted"),
                        id))
                .containsExactly("status New Open plain", "description null null redacted");
    }

    /**
     * Everything the load wrote for the run's cases: its ledger rows, and how many rows each table holds for them.
     */
    private List<String> written(long run) {
        List<String> written = new ArrayList<>(ledger(run));
        written.add("CASE_RECORD " + jdbc.queryForList(LEGACY_CASES, Long.class).size());
        for (String table : List.of(
                "CASE_COMMENT",
                "CASE_ATTACHMENT",
                "CASE_IMPORTED_EVENT",
                "CLOSURE_REQUEST",
                "LEGAL_HOLD_EVENT",
                "ALERT_LEDGER",
                "IMPORT_OWNER_PENDING",
                "AUDIT_EVENT")) {
            // Test-only counting over the fixed table names of this list.
            written.add(table + " "
                    + jdbc.queryForObject(
                            "SELECT COUNT(*) FROM " + table + " WHERE CASE_ID IN (" + LEGACY_CASES + ")",
                            Integer.class));
        }
        return written;
    }

    private String status(long run) {
        return String.valueOf(jdbc.queryForObject("SELECT STATUS FROM IMPORT_RUN WHERE ID = ?", String.class, run));
    }

    /** A block's lines, each with its runs of spaces made one, in sorted order. */
    private static List<String> lines(String block) {
        return block.lines()
                .map(line -> line.trim().replaceAll(" +", " "))
                .filter(line -> !line.isEmpty())
                .sorted()
                .toList();
    }

    private static String[] source(String... more) {
        List<String> args = new ArrayList<>(List.of(
                "--import.source.url=" + LEGACY.getJdbcUrl(),
                "--import.source.user=trailkeep_source",
                "--import.source.password=" + PASSWORD));
        args.addAll(List.of(more));
        return args.toArray(String[]::new);
    }
}
