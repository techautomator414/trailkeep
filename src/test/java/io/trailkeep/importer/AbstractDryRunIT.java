// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.testsupport.CaseSeed;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * {@code dry-run} as the import user (WP-26 part q, design §3 step 3): staged cases checked against the teams and case
 * types (with their outcomes, read through {@code casefile}'s import API) in pages, each problem a keyed issue, the
 * error rate against {@code limits.maxErrorRate}, the scanning mode in force recorded, a repeat replacing the last
 * one's issues, and nothing written to the domain; comments, attachments and history events checked against their
 * staged cases and their own rules (part q2), each entity with its own error rate, extract issues counted and {@code
 * limits.acceptSkip} codes left out. The staged rows are seeded by the schema owner.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
abstract class AbstractDryRunIT extends ExtractJobSupport {

    private static final String INSERT =
            "INSERT INTO IMPORT_STG_CASE (RUN_ID, LEGACY_KEY, ROW_HASH, TITLE, DESCRIPTION,"
                    + " TEAM_KEY, CASE_TYPE_KEY, STATUS, IDENTIFIED_ON, OPENED_AT, CLOSED_AT, CLOSURE_OUTCOME)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    @Autowired
    private ImportIssueRepository extractIssues;

    private long team;
    private long caseType;

    @BeforeEach
    void seedTheReferenceData() {
        CaseSeed seed = new CaseSeed(jdbc);
        team = seed.team();
        caseType = seed.caseType();
        jdbc.update(
                "INSERT INTO CASE_TYPE_OUTCOME (CASE_TYPE_ID, OUTCOME, SORT_ORDER) VALUES (?, ?, 1)",
                caseType,
                "SUBSTANTIATED");
    }

    @AfterEach
    void removeTheReferenceData() {
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE_OUTCOME WHERE CASE_TYPE_ID = ?", caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
    }

    @Test
    void dryRunRecordsEachCasesIssuesAndTheScanningModeAndMayRunAgain(@TempDir Path directory) throws Exception {
        Path mapping = mapping(directory, MAPPING);
        long run = extractedRun(mapping);
        List<Object[]> rows = new ArrayList<>();
        rows.add(row(run, "c0001", "Synthetic closed case", "CLOSED", "SUBSTANTIATED"));
        for (int i = 2; i <= 501; i++) {
            rows.add(row(run, String.format("c%04d", i), "Synthetic open case", "OPEN", null));
        }
        // Past the first page of 500, in key order.
        rows.add(row(run, "z1", "Synthetic closed case", "CLOSED", "NOT_ONE_OF_ITS_OUTCOMES"));
        rows.add(row(run, "z2", null, "OPEN", null));
        jdbc.batchUpdate(INSERT, rows);
        List<List<String>> expected = List.of(
                List.of("CASE", "FATAL", "CLOSURE_OUTCOME_NOT_ALLOWED", hash("z1")),
                List.of("CASE", "ERROR", "TITLE_MISSING", hash("z2")),
                List.of("CASE", "FATAL", "ERROR_RATE_EXCEEDED", ""));

        dryRun(run, mapping, "--app.attachments.scanning=external").close();

        assertThat(jdbc.queryForMap("SELECT STATUS, SCAN_MODE FROM IMPORT_RUN WHERE ID = ?", run))
                .containsEntry("STATUS", "DRY_RUN")
                .containsEntry("SCAN_MODE", "EXTERNAL");
        assertThat(dryRunIssues(run)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(count("SELECT DETAIL FROM IMPORT_ISSUE WHERE RUN_ID = ? AND CODE = 'ERROR_RATE_EXCEEDED'", run))
                .as("the cases with an ERROR")
                .isEqualTo(1);

        dryRun(run, mapping).close();

        assertThat(jdbc.queryForObject("SELECT SCAN_MODE FROM IMPORT_RUN WHERE ID = ?", String.class, run))
                .as("clamd unless the deployment says otherwise")
                .isEqualTo("CLAMD");
        assertThat(dryRunIssues(run)).as("replaced, not added to").containsExactlyInAnyOrderElementsOf(expected);
        assertThat(count("SELECT COUNT(*) FROM CASE_RECORD WHERE TEAM_ID = ?", team))
                .as("nothing reaches the domain")
                .isZero();
    }

    @Test
    void dryRunChecksCommentsAttachmentsAndEventsAgainstTheirCasesAndTheirOwnErrorRates(@TempDir Path directory)
            throws Exception {
        Path mapping = mapping(
                directory,
                MAPPING.replace(
                        "limits: { maxErrorRate: 0.0, batchSize: 2 }",
                        "limits: { maxErrorRate: 0.5, batchSize: 2,"
                                + " acceptSkip: [ATTACHMENT_TOO_LARGE, ATTACHMENT_MISSING] }"));
        long run = extractedRun(mapping);
        stageChildren(run);
        // What the extract found: a comment field it could not read, and the second file missing.
        extractIssue(run, ImportIssue.Subject.COMMENT, "n1", "BAD_FLAG");
        extractIssue(run, ImportIssue.Subject.ATTACHMENT, "f2", "ATTACHMENT_MISSING");

        dryRun(run, mapping, "--import.attachments.max-bytes=1000").close();

        assertThat(dryRunIssues(run))
                .containsExactlyInAnyOrder(
                        List.of("COMMENT", "ERROR", "PARENT_NOT_STAGED", hash("n2")),
                        List.of("COMMENT", "FATAL", "ERROR_RATE_EXCEEDED", ""),
                        List.of("ATTACHMENT", "WARNING", "ATTACHMENT_TOO_LARGE", hash("f1")),
                        List.of("ATTACHMENT", "WARNING", "ATTACHMENT_MISSING", hash("f2")),
                        List.of("EVENT", "ERROR", "KIND_INVALID", hash("e1")),
                        List.of("EVENT", "FATAL", "ERROR_RATE_EXCEEDED", ""));
        assertThat(count("SELECT DETAIL FROM IMPORT_ISSUE WHERE RUN_ID = ? AND CODE = 'ATTACHMENT_TOO_LARGE'", run))
                .as("the staged size against import.attachments.max-bytes")
                .isEqualTo(1001);
        assertThat(count(
                        "SELECT DETAIL FROM IMPORT_ISSUE WHERE RUN_ID = ? AND ENTITY = 'COMMENT'"
                                + " AND CODE = 'ERROR_RATE_EXCEEDED'",
                        run))
                .as("both comments failed, one at extract: over half of them")
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT STATUS FROM IMPORT_RUN WHERE ID = ?", String.class, run))
                .isEqualTo("DRY_RUN");
    }

    /** One sound case, a comment on it and one on no staged case, three attachments and an event of no known kind. */
    private void stageChildren(long run) {
        jdbc.update(INSERT, row(run, "c1", "Synthetic open case", "OPEN", null));
        Timestamp at = Timestamp.from(Instant.parse("2024-01-16T10:00:00Z"));
        String comment = "INSERT INTO IMPORT_STG_COMMENT (RUN_ID, LEGACY_KEY, ROW_HASH, CASE_KEY, WRITTEN_AT, BODY)"
                + " VALUES (?, ?, ?, ?, ?, ?)";
        jdbc.update(comment, run, "n1", "ab".repeat(32), "c1", at, "Synthetic note");
        jdbc.update(comment, run, "n2", "ab".repeat(32), "c9", at, "Synthetic note on no staged case");
        String attachment = "INSERT INTO IMPORT_STG_ATTACHMENT (RUN_ID, LEGACY_KEY, ROW_HASH, CASE_KEY, FILE_NAME,"
                + " SIZE_BYTES, SHA256, CONTENT_POINTER, UPLOADED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbc.update(attachment, run, "f1", "ab".repeat(32), "c1", "large.pdf", 1001L, null, "large.pdf", at);
        jdbc.update(attachment, run, "f2", "ab".repeat(32), "c1", "gone.pdf", null, null, "gone.pdf", at);
        jdbc.update(attachment, run, "f3", "ab".repeat(32), "c1", "fine.pdf", 10L, "cd".repeat(32), "fine.pdf", at);
        jdbc.update(
                "INSERT INTO IMPORT_STG_EVENT (RUN_ID, LEGACY_KEY, ROW_HASH, CASE_KEY, EVENT_AT, KIND)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                run,
                "e1",
                "ab".repeat(32),
                "c1",
                at,
                "NOT_A_KIND");
    }

    private void extractIssue(long run, ImportIssue.Subject subject, String key, String code) {
        extractIssues.save(new ImportIssue(
                run, ImportIssue.Stage.EXTRACT, subject, hash(key), Severity.ERROR, code, null, Instant.now()));
    }

    @Test
    void dryRunRefusesRunNotExtractedOrWhoseMappingChanged(@TempDir Path directory) throws Exception {
        Path mapping = mapping(directory, MAPPING);
        long validated = validatedRun(mapping);
        assertThatThrownBy(() -> dryRun(validated, mapping))
                .hasMessage("Import run " + validated + " is VALIDATED, not extracted and awaiting its sign-off");

        long extracted = extractedRun(mapping);
        Files.writeString(mapping, MAPPING.replace("batchSize: 2", "batchSize: 3"));
        assertThatThrownBy(() -> dryRun(extracted, mapping))
                .hasMessage("The mapping file or a lookup file changed after validate-mapping; validate it again");
        assertThat(jdbc.queryForObject("SELECT STATUS FROM IMPORT_RUN WHERE ID = ?", String.class, extracted))
                .isEqualTo("EXTRACTED");
    }

    private long extractedRun(Path mapping) throws Exception {
        long run = validatedRun(mapping);
        jdbc.update("UPDATE IMPORT_RUN SET STATUS = 'EXTRACTED', SOURCE_FINGERPRINT = 'memory-fp-1' WHERE ID = ?", run);
        return run;
    }

    private Object[] row(long run, String key, @Nullable String title, String status, @Nullable String outcome) {
        boolean closed = "CLOSED".equals(status);
        return new Object[] {
            run,
            key,
            "ab".repeat(32),
            title,
            "Synthetic description",
            "Seed Team " + team,
            "Seed " + caseType,
            status,
            Date.valueOf(LocalDate.of(2024, 1, 14)),
            Timestamp.from(Instant.parse("2024-01-15T10:00:00Z")),
            closed ? Timestamp.from(Instant.parse("2024-03-01T10:00:00Z")) : null,
            outcome
        };
    }

    private ConfigurableApplicationContext dryRun(long run, Path mapping, String... more) throws SQLException {
        List<String> args = new ArrayList<>(List.of(
                "--import.command=dry-run",
                "--import.run=" + run,
                "--import.mapping=" + mapping,
                "--import.hash-key=" + Base64.getEncoder().encodeToString(HASH_KEY)));
        args.addAll(List.of(more));
        return start(args.toArray(String[]::new));
    }

    private List<List<String>> dryRunIssues(long run) {
        return jdbc.query(
                "SELECT ENTITY, SEVERITY, CODE, KEY_HASH FROM IMPORT_ISSUE WHERE RUN_ID = ? AND STAGE = 'DRY_RUN'",
                (row, index) -> List.of(
                        row.getString(1),
                        row.getString(2),
                        row.getString(3),
                        row.getString(4) == null ? "" : row.getString(4)),
                run);
    }
}
