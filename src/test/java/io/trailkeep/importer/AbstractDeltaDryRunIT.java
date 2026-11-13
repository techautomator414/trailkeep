// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code dry-run} of a delta run, as the import user (WP-26 part y1, design §3.1, R10): a second run of a source whose
 * first run was loaded. Rows the import ledger holds are checked only for what changed in the source: an open case
 * closed there is {@code CLOSED_IN_SOURCE}, another change of status or closing time or of the row hash {@code
 * CHANGED_AFTER_IMPORT}, each carrying the Trailkeep case id; ledger rows the run did not stage are {@code
 * MISSING_IN_SOURCE}; a comment of a loaded case the run did not stage has its parent. A run under another mapping is
 * FATAL unless {@code import.allow-mapping-change} is set, which turns off the row-hash comparison and nothing else. A
 * loaded case archived or purged since, and each new child of it, is a WARNING carrying the case id (26y2).
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
abstract class AbstractDeltaDryRunIT extends LoadSupport {

    private static final String INSERT_COMMENT = "INSERT INTO IMPORT_STG_COMMENT (RUN_ID, LEGACY_KEY, ROW_HASH,"
            + " CASE_KEY, WRITTEN_AT, BODY, BODY_FORMAT) VALUES (?, ?, ?, ?, ?, ?, 'plain')";

    /** The load's mapping, with room for a delta's errors. */
    private static final String TOLERANT =
            MAPPING.replace("limits: { maxErrorRate: 0.0,", "limits: { maxErrorRate: 0.9,");

    private static final String CHANGED_HASH = "cd".repeat(32);

    @Test
    void deltaReportsChangedClosedAndMissingRowsAndTakesChildrenOfLoadedCases(@TempDir Path directory)
            throws Exception {
        Path mapping = ownedMapping(directory, TOLERANT);
        long first = signedOffRun(mapping);
        for (String key : List.of("c1", "c2", "c3")) {
            stage(first, key, "OPEN", "p1");
        }
        stage(first, "c4", "CLOSED", "p1");
        comment(first, "n1", "c1", "ab".repeat(32));
        comment(first, "n2", "c4", "ab".repeat(32));
        load(first, mapping).close();
        long delta = extractedRun(mapping);
        stage(delta, "c1", "OPEN", "p1");
        // Changed in the source, and now failing the case rules, which a loaded case is no longer checked against.
        stage(delta, "c2", "OPEN", "p1");
        jdbc.update(
                "UPDATE IMPORT_STG_CASE SET TITLE = NULL, ROW_HASH = ? WHERE RUN_ID = ? AND LEGACY_KEY = 'c2'",
                CHANGED_HASH,
                delta);
        stage(delta, "c3", "CLOSED", "p1");
        stage(delta, "c5", "OPEN", "p1");
        comment(delta, "n2", "c4", "ab".repeat(32));
        comment(delta, "n3", "c4", "ab".repeat(32));
        comment(delta, "n4", "cx", "ab".repeat(32));

        dryRun(delta, mapping).close();

        assertThat(dryRunIssues(delta))
                .containsExactlyInAnyOrder(
                        List.of("CASE", "WARNING", "CHANGED_AFTER_IMPORT", hash("c2")),
                        List.of("CASE", "WARNING", "CLOSED_IN_SOURCE", hash("c3")),
                        List.of("CASE", "WARNING", "MISSING_IN_SOURCE", hash("c4")),
                        List.of("COMMENT", "WARNING", "MISSING_IN_SOURCE", hash("n1")),
                        List.of("COMMENT", "ERROR", "PARENT_NOT_STAGED", hash("n4")));
        assertThat(details(delta))
                .as("each carries the Trailkeep case id, never a legacy value")
                .containsExactlyInAnyOrder(
                        "CHANGED_AFTER_IMPORT " + caseId("c2"),
                        "CLOSED_IN_SOURCE " + caseId("c3"),
                        "MISSING_IN_SOURCE " + caseId("c4"),
                        "MISSING_IN_SOURCE " + caseId("c1"),
                        "PARENT_NOT_STAGED -");
        assertThat(figures(delta))
                .contains("ROWS CASE 4", "LOADED_BEFORE CASE 3", "ROWS COMMENT 3", "LOADED_BEFORE COMMENT 1")
                .as("only the new case is counted as one to load")
                .contains("CASES OPEN 1")
                .noneMatch(figure -> figure.startsWith("CASES CLOSED"));
        assertThat(loadedCases("memory1:%")).as("nothing is written").isEqualTo(4);
    }

    @Test
    void deltaUnderAnotherMappingIsFatalUnlessAllowedWhichStopsOnlyTheHashComparison(@TempDir Path directory)
            throws Exception {
        Path mapping = ownedMapping(directory, TOLERANT);
        long first = signedOffRun(mapping);
        stage(first, "c1", "OPEN", "p1");
        stage(first, "c2", "CLOSED", "p1");
        stage(first, "c3", "OPEN", "p1");
        load(first, mapping).close();
        Path changed = ownedMapping(directory, TOLERANT.replace("batchSize: 2", "batchSize: 3"));
        long delta = extractedRun(changed);
        stage(delta, "c1", "CLOSED", "p1");
        stage(delta, "c2", "CLOSED", "p1");
        jdbc.update(
                "UPDATE IMPORT_STG_CASE SET CLOSED_AT = ? WHERE RUN_ID = ? AND LEGACY_KEY = 'c2'",
                Timestamp.from(Instant.parse("2024-03-02T10:00:00Z")),
                delta);
        stage(delta, "c3", "OPEN", "p1");
        jdbc.update("UPDATE IMPORT_STG_CASE SET ROW_HASH = ? WHERE RUN_ID = ?", CHANGED_HASH, delta);

        dryRun(delta, changed).close();

        assertThat(dryRunIssues(delta))
                .containsExactlyInAnyOrder(
                        List.of("MAPPING", "FATAL", "MAPPING_CHANGED_SINCE_LOAD", ""),
                        List.of("CASE", "WARNING", "CLOSED_IN_SOURCE", hash("c1")),
                        List.of("CASE", "WARNING", "CHANGED_AFTER_IMPORT", hash("c2")),
                        List.of("CASE", "WARNING", "CHANGED_AFTER_IMPORT", hash("c3")));
        assertThat(details(delta)).as("the ledger rows under another mapping").contains("MAPPING_CHANGED_SINCE_LOAD 3");

        dryRun(delta, changed, "--import.allow-mapping-change=true").close();

        assertThat(dryRunIssues(delta))
                .as("status and closing time are still compared (R10), row hashes no longer")
                .containsExactlyInAnyOrder(
                        List.of("MAPPING", "WARNING", "MAPPING_CHANGE_ALLOWED", ""),
                        List.of("CASE", "WARNING", "CLOSED_IN_SOURCE", hash("c1")),
                        List.of("CASE", "WARNING", "CHANGED_AFTER_IMPORT", hash("c2")));
        assertThat(details(delta)).contains("MAPPING_CHANGE_ALLOWED 3", "CLOSED_IN_SOURCE " + caseId("c1"));
    }

    @Test
    void deltaWarnsOfLoadedCasesArchivedOrPurgedSinceAndOfTheirNewChildren(@TempDir Path directory) throws Exception {
        Path mapping = ownedMapping(directory, TOLERANT);
        long first = signedOffRun(mapping);
        stage(first, "c1", "CLOSED", "p1");
        stage(first, "c2", "CLOSED", "p1");
        stage(first, "c3", "OPEN", "p1");
        load(first, mapping).close();
        archive(caseId("c1"), false);
        archive(caseId("c2"), true);
        long delta = extractedRun(mapping);
        stage(delta, "c1", "CLOSED", "p1");
        jdbc.update(
                "UPDATE IMPORT_STG_CASE SET ROW_HASH = ? WHERE RUN_ID = ? AND LEGACY_KEY = 'c1'", CHANGED_HASH, delta);
        stage(delta, "c3", "OPEN", "p1");
        comment(delta, "n1", "c1", "ab".repeat(32));
        comment(delta, "n2", "c2", "ab".repeat(32));
        comment(delta, "n3", "c3", "ab".repeat(32));

        dryRun(delta, mapping).close();

        assertThat(dryRunIssues(delta))
                .as("the archive state in place of any other change, and of the children's rules")
                .containsExactlyInAnyOrder(
                        List.of("CASE", "WARNING", "CASE_ARCHIVED", hash("c1")),
                        List.of("CASE", "WARNING", "MISSING_IN_SOURCE", hash("c2")),
                        List.of("COMMENT", "WARNING", "PARENT_ARCHIVED", hash("n1")),
                        List.of("COMMENT", "WARNING", "PARENT_PURGED", hash("n2")));
        assertThat(details(delta))
                .containsExactlyInAnyOrder(
                        "CASE_ARCHIVED " + caseId("c1"),
                        "MISSING_IN_SOURCE " + caseId("c2"),
                        "PARENT_ARCHIVED " + caseId("c1"),
                        "PARENT_PURGED " + caseId("c2"));
    }

    private void comment(long run, String key, String caseKey, String rowHash) {
        jdbc.update(
                INSERT_COMMENT,
                run,
                key,
                rowHash,
                caseKey,
                Timestamp.from(Instant.parse("2024-02-01T09:00:00Z")),
                "Synthetic note");
    }

    private long caseId(String key) {
        return number(loadedCase(key), "ID");
    }

    /** The dry run's issues as code and detail ("-" for none). */
    private List<String> details(long run) {
        return jdbc.query(
                "SELECT CODE, DETAIL FROM IMPORT_ISSUE WHERE RUN_ID = ? AND STAGE = 'DRY_RUN'",
                (row, index) -> row.getString(1) + " " + (row.getObject(2) == null ? "-" : row.getLong(2)),
                run);
    }
}
