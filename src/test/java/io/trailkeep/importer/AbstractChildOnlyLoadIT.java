// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.ImportIssue.Subject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code load} of a delta run, as the import user (WP-26 part y2, design §3.1, §8.2): new comments, attachments and
 * history events of cases a first run loaded are written onto those cases, found through the import ledger, whether
 * the delta staged the case again or not; children the ledger holds are not written again, one with an ERROR is left
 * out, an attachment the first load left out is tried again, each new child gets its ledger row, no case is created
 * and a second load writes nothing more. A case whose new children fail now is a {@code LOAD} issue, retried by the
 * next load. A case archived or purged since it was loaded takes nothing, which every load records as a {@code LOAD}
 * issue.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
abstract class AbstractChildOnlyLoadIT extends LoadSupport {

    private static final String INSERT_COMMENT = "INSERT INTO IMPORT_STG_COMMENT (RUN_ID, LEGACY_KEY, ROW_HASH,"
            + " CASE_KEY, AUTHOR_KEY, WRITTEN_AT, BODY, BODY_FORMAT) VALUES (?, ?, ?, ?, 'p1', ?, ?, 'plain')";

    private static final String INSERT_EVENT = "INSERT INTO IMPORT_STG_EVENT (RUN_ID, LEGACY_KEY, ROW_HASH, CASE_KEY,"
            + " EVENT_AT, KIND, FIELD_NAME, OLD_VALUE, NEW_VALUE) VALUES (?, ?, ?, ?, ?, ?, 'status', 'A', 'B')";

    private static final String INSERT_ATTACHMENT = "INSERT INTO IMPORT_STG_ATTACHMENT (RUN_ID, LEGACY_KEY, ROW_HASH,"
            + " CASE_KEY, FILE_NAME, SIZE_BYTES, SHA256, CONTENT_POINTER, UPLOADED_AT, UPLOADER_KEY)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'p1')";

    private static final Instant WRITTEN = Instant.parse("2024-02-01T09:00:00Z");

    private static final String EXTERNAL = "--app.attachments.scanning=external";

    @Test
    void deltaLoadWritesNewChildrenOntoLoadedCasesOnceAndRetriesWhatTheFirstLoadLeftOut(@TempDir Path directory)
            throws Exception {
        Path files = Files.createDirectories(directory.resolve("files")).toRealPath();
        Path mapping = ownedMapping(directory, MAPPING + "attachments: { root: \"" + files + "\" }\n");
        final String sha256 = ValidateMappingCommand.sha256(
                Files.readAllBytes(Files.writeString(files.resolve("a1.txt"), "Synthetic legacy note")));
        long first = externalRun(mapping);
        stage(first, "c1", "OPEN", "p1");
        stage(first, "c2", "OPEN", "p1");
        comment(first, "n1", "c1");
        event(first, "e1", "c1", "STATUS_CHANGE");
        attach(first, "a1", "c1", sha256);
        error(first, Subject.ATTACHMENT, "a1");
        load(first, mapping, EXTERNAL).close();
        long c1 = number(loadedCase("c1"), "ID");
        final long c2 = number(loadedCase("c2"), "ID");
        assertThat(count("SELECT COUNT(*) FROM CASE_ATTACHMENT WHERE CASE_ID = ?", c1))
                .as("left out by the first load, with no ledger row")
                .isZero();

        long delta = externalRun(mapping);
        stage(delta, "c1", "OPEN", "p1");
        comment(delta, "n1", "c1");
        comment(delta, "n2", "c1");
        comment(delta, "n3", "c2");
        comment(delta, "n4", "c2");
        error(delta, Subject.COMMENT, "n4");
        event(delta, "e1", "c1", "STATUS_CHANGE");
        event(delta, "e2", "c2", "STATUS_CHANGE");
        attach(delta, "a1", "c1", sha256);
        load(delta, mapping, EXTERNAL).close();

        assertThat(loadedCases("memory1:%")).as("no case is created").isEqualTo(2);
        assertThat(children(c1)).containsExactly(2, 1, 1);
        assertThat(children(c2)).containsExactly(1, 0, 1);
        assertThat(ledgerRows(delta)).containsExactly("ATTACHMENT a1", "COMMENT n2", "COMMENT n3", "EVENT e2");
        assertThat(count("SELECT COUNT(*) FROM AUDIT_EVENT WHERE ACTION = 'COMMENT_IMPORTED' AND CASE_ID = ?", c2))
                .isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT STATUS, ROWS_LOADED FROM IMPORT_RUN WHERE ID = ?", delta))
                .as("the run counts the cases it created")
                .containsEntry("STATUS", "LOADED")
                .hasEntrySatisfying(
                        "ROWS_LOADED",
                        rows -> assertThat(((Number) rows).intValue()).isZero());

        load(delta, mapping, EXTERNAL).close();

        assertThat(children(c1)).as("a second load writes nothing more").containsExactly(2, 1, 1);
        assertThat(children(c2)).containsExactly(1, 0, 1);
    }

    @Test
    void loadedCaseWhoseNewChildrenFailNowIsALoadIssueRetriedByTheNextLoad(@TempDir Path directory) throws Exception {
        Path mapping = mapping(directory);
        long first = signedOffRun(mapping);
        stage(first, "c1", "OPEN", "p1");
        load(first, mapping).close();
        final long c1 = number(loadedCase("c1"), "ID");
        long delta = signedOffRun(mapping);
        comment(delta, "n1", "c1");
        // Changed after the dry run: a kind the history table does not have.
        event(delta, "e1", "c1", "RENAMED");

        load(delta, mapping).close();

        assertThat(loadIssues(delta)).containsExactly("EVENT_KIND_INVALID " + hash("c1"));
        assertThat(children(c1)).as("the case's new children wait together").containsExactly(0, 0, 0);

        jdbc.update("UPDATE IMPORT_STG_EVENT SET KIND = 'FIELD_CHANGE' WHERE RUN_ID = ?", delta);
        load(delta, mapping).close();

        assertThat(loadIssues(delta)).isEmpty();
        assertThat(children(c1)).containsExactly(1, 0, 1);
    }

    @Test
    void archivedOrPurgedCaseTakesNothingAcrossLoadsAndIsALoadIssueEachTime(@TempDir Path directory) throws Exception {
        Path mapping = mapping(directory);
        long first = signedOffRun(mapping);
        stage(first, "c1", "CLOSED", "p1");
        stage(first, "c2", "CLOSED", "p1");
        stage(first, "c3", "OPEN", "p1");
        comment(first, "n1", "c2");
        load(first, mapping).close();
        final long c1 = number(loadedCase("c1"), "ID");
        final long c2 = number(loadedCase("c2"), "ID");
        final long c3 = number(loadedCase("c3"), "ID");
        archive(c1, false);
        archive(c2, true);
        long delta = signedOffRun(mapping);
        stage(delta, "c2", "CLOSED", "p1");
        comment(delta, "n1", "c2");
        for (String key : List.of("c1", "c2", "c3")) {
            comment(delta, "n" + key, key);
            event(delta, "e" + key, key, "FIELD_CHANGE");
        }

        load(delta, mapping).close();

        assertThat(loadIssues(delta)).containsExactly("CASE_ARCHIVED " + hash("c1"), "CASE_PURGED " + hash("c2"));
        assertThat(children(c1)).as("an archived case is read-only").containsExactly(0, 0, 0);
        assertThat(children(c2)).as("a purged case's content stays gone").containsExactly(0, 0, 0);
        assertThat(children(c3)).containsExactly(1, 0, 1);
        assertThat(ledgerRows(delta)).containsExactly("COMMENT nc3", "EVENT ec3");
        assertThat(count("SELECT COUNT(*) FROM CASE_SEARCH WHERE CASE_ID = ?", c2))
                .as("no search text either")
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM CASE_RECORD WHERE ID = ? AND DESCRIPTION IS NULL", c2))
                .isEqualTo(1);

        load(delta, mapping).close();

        assertThat(loadIssues(delta))
                .as("recorded again by every load while the case stays so")
                .containsExactly("CASE_ARCHIVED " + hash("c1"), "CASE_PURGED " + hash("c2"));
        assertThat(children(c1)).containsExactly(0, 0, 0);
        assertThat(children(c2)).containsExactly(0, 0, 0);
        assertThat(children(c3)).containsExactly(1, 0, 1);
    }

    /** A signed-off run whose dry run recorded the {@code external} scanning mode. */
    private long externalRun(Path mapping) throws Exception {
        long run = signedOffRun(mapping);
        jdbc.update("UPDATE IMPORT_RUN SET SCAN_MODE = 'EXTERNAL' WHERE ID = ?", run);
        return run;
    }

    private void comment(long run, String key, String caseKey) {
        jdbc.update(INSERT_COMMENT, run, key, "ab".repeat(32), caseKey, Timestamp.from(WRITTEN), "Synthetic " + key);
    }

    private void event(long run, String key, String caseKey, String kind) {
        jdbc.update(INSERT_EVENT, run, key, "ab".repeat(32), caseKey, Timestamp.from(WRITTEN), kind);
    }

    private void attach(long run, String key, String caseKey, String sha256) {
        jdbc.update(
                INSERT_ATTACHMENT,
                run,
                key,
                "ab".repeat(32),
                caseKey,
                key + ".txt",
                21L,
                sha256,
                key + ".txt",
                Timestamp.from(WRITTEN));
    }

    private void error(long run, Subject subject, String key) {
        issueRows.save(new ImportIssue(
                run, ImportIssue.Stage.DRY_RUN, subject, hash(key), Severity.ERROR, "AT_MISSING", null, Instant.now()));
    }

    /** A case's comments, attachments and imported history events, counted. */
    private List<Integer> children(long caseId) {
        return List.of(
                count("SELECT COUNT(*) FROM CASE_COMMENT WHERE CASE_ID = ?", caseId),
                count("SELECT COUNT(*) FROM CASE_ATTACHMENT WHERE CASE_ID = ?", caseId),
                count("SELECT COUNT(*) FROM CASE_IMPORTED_EVENT WHERE CASE_ID = ?", caseId));
    }

    /** The run's ledger rows as entity and key, in that order. */
    private List<String> ledgerRows(long run) {
        return jdbc.query(
                "SELECT ENTITY, LEGACY_KEY FROM IMPORT_LEDGER WHERE RUN_ID = ? ORDER BY ENTITY, LEGACY_KEY",
                (row, index) -> row.getString(1) + " " + row.getString(2),
                run);
    }
}
