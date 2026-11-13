// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.shared.csv.CsvFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * {@code dry-run} as the import user (WP-26 part q, design §3 step 3): staged cases checked against the teams and case
 * types (with their outcomes, read through {@code casefile}'s import API) in pages, each problem a keyed issue, the
 * error rate against {@code limits.maxErrorRate}, the scanning mode in force recorded, a repeat replacing the last
 * one's issues, and nothing written to the domain; comments, attachments and history events checked against their
 * staged cases and their own rules (part q2), each entity with its own error rate, extract issues counted and {@code
 * limits.acceptSkip} codes left out. With no default owners in the mapping, the seeded team's open cases make a FATAL
 * {@code DEFAULT_OWNER_MISSING} (part r2, tested in {@link AbstractDryRunPeopleIT}). The staged rows are seeded by the
 * schema owner.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
abstract class AbstractDryRunIT extends DryRunSupport {

    @Autowired
    private ImportIssueRepository extractIssues;

    @Value("${app.db.import-user}")
    private String importUser = "";

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
                List.of("CASE", "FATAL", "ERROR_RATE_EXCEEDED", ""),
                List.of("CASE", "FATAL", "DEFAULT_OWNER_MISSING", ""));

        dryRun(run, mapping, "--app.attachments.scanning=external").close();

        assertThat(jdbc.queryForMap("SELECT STATUS, SCAN_MODE FROM IMPORT_RUN WHERE ID = ?", run))
                .containsEntry("STATUS", "DRY_RUN")
                .containsEntry("SCAN_MODE", "EXTERNAL");
        assertThat(dryRunIssues(run)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(count("SELECT DETAIL FROM IMPORT_ISSUE WHERE RUN_ID = ? AND CODE = 'ERROR_RATE_EXCEEDED'", run))
                .as("the cases with an ERROR")
                .isEqualTo(1);
        assertThat(figures(run))
                .contains(
                        "ROWS CASE 503",
                        "CASES OPEN 501",
                        "CASES CLOSED 2",
                        "VISIBILITY_STANDARD " + team + " 503",
                        "ISSUES CASE/FATAL/CLOSURE_OUTCOME_NOT_ALLOWED 1",
                        "ISSUES CASE/ERROR/TITLE_MISSING 1",
                        "ROWS COMMENT 0",
                        "NOT_SCANNED_FILES - 0",
                        "SLA_FORECAST OVERDUE 501")
                .noneMatch(figure -> figure.startsWith("HOLDS") || figure.startsWith("LEGACY_ACL"));
        assertThePreviewHoldsFiguresAndKeyedIssuesOnly(directory.resolve(Long.toString(run)));

        dryRun(run, mapping, "--import.go-live=2100-01-04").close();

        assertThat(jdbc.queryForObject("SELECT SCAN_MODE FROM IMPORT_RUN WHERE ID = ?", String.class, run))
                .as("clamd unless the deployment says otherwise")
                .isEqualTo("CLAMD");
        assertThat(dryRunIssues(run)).as("replaced, not added to").containsExactlyInAnyOrderElementsOf(expected);
        assertThat(figures(run))
                .as("replaced, and no NOT_SCANNED figures under clamd")
                .containsOnlyOnce("ROWS CASE 503")
                .contains("SLA_FORECAST_DAY - " + LocalDate.of(2100, 1, 4).toEpochDay(), "SLA_FORECAST OVERDUE 501")
                .noneMatch(figure -> figure.startsWith("NOT_SCANNED"));
        assertThat(count("SELECT COUNT(*) FROM CASE_RECORD WHERE TEAM_ID = ?", team))
                .as("nothing reaches the domain")
                .isZero();
    }

    /** The preview report files (part ab2): figures and issues by code and keyed hash, no value, owner-only. */
    private void assertThePreviewHoldsFiguresAndKeyedIssuesOnly(Path folder) throws Exception {
        String csv = Files.readString(folder.resolve("dry-run.csv"));
        String html = Files.readString(folder.resolve("dry-run.html"));
        assertThat(csv)
                .startsWith(CsvFormat.BOM + "\"row\",\"metric\",")
                .contains("\"figure\",\"CASES\",\"OPEN\",\"501\",")
                .contains("\"issue\",\"\",\"\",\"\",\"CASE\",\"DRY_RUN\",\"ERROR\",\"TITLE_MISSING\",\"" + hash("z2")
                        + "\",\"\"");
        assertThat(html)
                .contains("<th scope=\"row\">CASE</th><td>DRY_RUN</td><td>FATAL</td>"
                        + "<td>CLOSURE_OUTCOME_NOT_ALLOWED</td><td>" + hash("z1") + "</td>");
        assertThat(csv + html).as("no staged value or key").doesNotContain("Synthetic", "\"z1\"", ">z2<", "c0001");
        if (folder.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(folder)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
            assertThat(Files.getPosixFilePermissions(folder.resolve("dry-run.csv")))
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
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

        dryRun(run, mapping, "--import.attachments.max-bytes=1000", "--app.attachments.scanning=external")
                .close();

        assertThat(dryRunIssues(run))
                .containsExactlyInAnyOrder(
                        List.of("CASE", "FATAL", "DEFAULT_OWNER_MISSING", ""),
                        List.of("COMMENT", "ERROR", "PARENT_NOT_STAGED", hash("n2")),
                        List.of("COMMENT", "FATAL", "ERROR_RATE_EXCEEDED", ""),
                        List.of("ATTACHMENT", "WARNING", "ATTACHMENT_TOO_LARGE", hash("f1")),
                        List.of("ATTACHMENT", "WARNING", "ATTACHMENT_MISSING", hash("f2")),
                        List.of("ATTACHMENT", "WARNING", "NOT_SCANNED", ""),
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
        assertChildFigures(run);
    }

    /** The children's figures: rows per entity, issues from both stages, loadable, skipped and unscanned files. */
    private void assertChildFigures(long run) {
        assertThat(count("SELECT DETAIL FROM IMPORT_ISSUE WHERE RUN_ID = ? AND CODE = 'NOT_SCANNED'", run))
                .as("the one loadable file will be stored NOT_SCANNED")
                .isEqualTo(1);
        assertThat(figures(run))
                .contains(
                        "ROWS CASE 1",
                        "ROWS COMMENT 2",
                        "ROWS ATTACHMENT 3",
                        "ROWS EVENT 1",
                        "ISSUES COMMENT/ERROR/BAD_FLAG 1",
                        "ISSUES ATTACHMENT/ERROR/ATTACHMENT_MISSING 1",
                        "ISSUES ATTACHMENT/WARNING/ATTACHMENT_MISSING 1",
                        "ISSUES ATTACHMENT/WARNING/NOT_SCANNED 1",
                        "ATTACHMENT_FILES - 1",
                        "ATTACHMENT_BYTES - 10",
                        "SKIPPED_FILES ATTACHMENT_TOO_LARGE 1",
                        "SKIPPED_BYTES ATTACHMENT_TOO_LARGE 1001",
                        "SKIPPED_FILES ATTACHMENT_MISSING 1",
                        "SKIPPED_BYTES ATTACHMENT_MISSING 0",
                        "NOT_SCANNED_FILES - 1",
                        "NOT_SCANNED_BYTES - 10");
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
    void dryRunFailingAfterItsFirstPageLeavesTheRunExtractedWithoutFigures(@TempDir Path directory) throws Exception {
        Path mapping = mapping(directory, MAPPING);
        long run = extractedRun(mapping);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= 500; i++) {
            rows.add(row(run, String.format("c%04d", i), "Synthetic open case", "OPEN", null));
        }
        // On the second page: the first issue to write.
        rows.add(row(run, "z1", null, "OPEN", null));
        jdbc.batchUpdate(INSERT, rows);
        dryRun(run, mapping).close();
        assertThat(jdbc.queryForObject("SELECT STATUS FROM IMPORT_RUN WHERE ID = ?", String.class, run))
                .isEqualTo("DRY_RUN");
        assertThat(figures(run)).isNotEmpty();

        // The import user can no longer write issues, so the repeat fails writing its second page.
        jdbc.update("REVOKE INSERT ON IMPORT_ISSUE FROM " + importUser);
        try {
            assertThatThrownBy(() -> dryRun(run, mapping)).isNotNull();
        } finally {
            jdbc.update("GRANT INSERT ON IMPORT_ISSUE TO " + importUser);
        }

        assertThat(jdbc.queryForMap("SELECT STATUS, SCAN_MODE FROM IMPORT_RUN WHERE ID = ?", run))
                .as("DRY_RUN means a dry run that completed")
                .containsEntry("STATUS", "EXTRACTED")
                .containsEntry("SCAN_MODE", null);
        assertThat(figures(run))
                .as("no figures from a dry run that did not finish")
                .isEmpty();
        assertThat(dryRunIssues(run)).isEmpty();
    }

    @Test
    void dryRunRefusesRunNotExtractedOrWhoseMappingOrStreamLimitIsWrong(@TempDir Path directory) throws Exception {
        Path mapping = mapping(directory, MAPPING);
        long validated = validatedRun(mapping);
        assertThatThrownBy(() -> dryRun(validated, mapping))
                .hasMessage("Import run " + validated + " is VALIDATED, not extracted and awaiting its sign-off");

        long extracted = extractedRun(mapping);
        assertThatThrownBy(() -> command("dry-run", extracted, mapping))
                .as("no reports directory, refused before anything is read")
                .hasStackTraceContaining("import.reports-dir must name a directory");
        assertThatThrownBy(() -> dryRun(extracted, mapping, "--import.attachments.clamd-stream-max=24MB"))
                .as("clamd's declared stream limit is below the importer's cap")
                .hasMessage("In clamd scanning mode, import.attachments.clamd-stream-max must declare clamd's"
                        + " StreamMaxLength and be at least import.attachments.max-bytes");
        Files.writeString(mapping, MAPPING.replace("batchSize: 2", "batchSize: 3"));
        assertThatThrownBy(() -> dryRun(extracted, mapping))
                .hasMessage("The mapping file or a lookup file changed after validate-mapping; validate it again");
        assertThat(jdbc.queryForObject("SELECT STATUS FROM IMPORT_RUN WHERE ID = ?", String.class, extracted))
                .isEqualTo("EXTRACTED");
    }
}
