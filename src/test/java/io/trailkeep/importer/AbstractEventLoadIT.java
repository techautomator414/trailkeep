// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.ImportIssue.Subject;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code load} with legacy history, as the import user (WP-26 part w, design §7.5, §8.2): each case's staged events
 * written into {@code CASE_IMPORTED_EVENT} in its transaction, in the order they happened, with the legacy time and a
 * name snapshot of the actor; only the values of the six plain fields of an event that is not a note stored as text,
 * cut to 1000 characters and never inside a surrogate pair, every other value only as its HMAC and length; an event
 * with an ERROR left out; each event given its ledger row; and an event that fails its rules at the load's moment
 * keeping its case out until it is fixed.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
abstract class AbstractEventLoadIT extends LoadSupport {

    private static final String INSERT_EVENT = "INSERT INTO IMPORT_STG_EVENT (RUN_ID, LEGACY_KEY, ROW_HASH, CASE_KEY,"
            + " EVENT_AT, WHO_KEY, WHO_NAME, KIND, FIELD_NAME, OLD_VALUE, NEW_VALUE)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** One character outside the Basic Multilingual Plane: two UTF-16 halves. */
    private static final String FACE = new String(Character.toChars(0x1F600));

    @Test
    void loadWritesEachCasesHistoryWithNameSnapshotsAndOnlyPlainFieldsAsText(@TempDir Path directory) throws Exception {
        Path mapping = mapping(directory);
        long run = signedOffRun(mapping);
        stage(run, "c1", "OPEN", "p1");
        stage(run, "c2", "OPEN", "p1");
        Event status = new Event("STATUS_CHANGE", "status", "OPEN", "PENDING");
        event(run, "e1", "c1", "2024-01-16T10:00:00Z", "p1", null, status);
        Event description = new Event("FIELD_CHANGE", "description", "Synthetic before", "Synthetic after!");
        event(run, "e2", "c1", "2024-01-17T10:00:00Z", "p1", "Synthetic Typed Actor", description);
        event(run, "e3", "c1", "2024-01-18T10:00:00Z", null, null, new Event("NOTE", "status", null, "Synthetic"));
        Event title = new Event("FIELD_CHANGE", "title", "a".repeat(1500), "x".repeat(999) + FACE);
        event(run, "e4", "c1", "2024-01-19T10:00:00Z", "p2", null, title);
        event(run, "e5", "c1", "2024-01-20T10:00:00Z", null, null, new Event("OTHER", "other", "old", null));
        event(run, "e6", "c1", "2024-01-21T10:00:00Z", null, null, status);
        event(run, "e7", "c2", "2024-01-21T10:00:00Z", null, null, status);
        error(run, Subject.EVENT, "e6");
        error(run, Subject.CASE, "c2");

        load(run, mapping).close();

        long caseId = number(loadedCase("c1"), "ID");
        assertThat(history(caseId))
                .containsExactly(
                        "2024-01-16T10:00:00Z Synthetic Matched Owner STATUS_CHANGE status OPEN PENDING " + run,
                        "2024-01-17T10:00:00Z Synthetic Typed Actor FIELD_CHANGE description #16 #16 " + run,
                        "2024-01-18T10:00:00Z - NOTE status - #9 " + run,
                        "2024-01-19T10:00:00Z Synthetic Legacy Owner FIELD_CHANGE title " + "a".repeat(1000) + " "
                                + "x".repeat(999) + " " + run,
                        "2024-01-20T10:00:00Z - OTHER other #3 - " + run);
        assertThat(jdbc.queryForList(
                        "SELECT OLD_HMAC FROM CASE_IMPORTED_EVENT WHERE CASE_ID = ? AND OLD_HMAC IS NOT NULL",
                        String.class,
                        caseId))
                .hasSize(2)
                .allSatisfy(hmac -> assertThat(hmac).hasSize(64));
        assertThat(jdbc.query(
                        "SELECT L.LEGACY_KEY FROM IMPORT_LEDGER L JOIN CASE_IMPORTED_EVENT E ON E.ID = L.TARGET_ID"
                                + " WHERE L.RUN_ID = ? AND L.ENTITY = 'EVENT' AND L.CASE_ID = ? ORDER BY L.LEGACY_KEY",
                        (row, index) -> row.getString(1),
                        run,
                        caseId))
                .containsExactly("e1", "e2", "e3", "e4", "e5");
        assertThat(loadedCases("memory1:%"))
                .as("the case with an ERROR is skipped, with its history")
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM CASE_IMPORTED_EVENT WHERE RUN_ID = ?", run))
                .isEqualTo(5);
    }

    @Test
    void eventThatFailsItsRulesAtTheLoadKeepsItsCaseOutUntilFixed(@TempDir Path directory) throws Exception {
        Path mapping = mapping(directory);
        long run = signedOffRun(mapping);
        stage(run, "c1", "OPEN", "p1");
        event(run, "e1", "c1", "2024-01-16T10:00:00Z", "p1", null, new Event("STATUS_CHANGE", "status", "A", "B"));
        // Changed after the dry run: a kind the history table does not have.
        event(run, "e2", "c1", "2024-01-17T10:00:00Z", "p1", null, new Event("RENAMED", "title", "A", "B"));

        load(run, mapping).close();

        assertThat(loadIssues(run)).containsExactly("EVENT_KIND_INVALID " + hash("c1"));
        assertThat(loadedCases("memory1:%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM CASE_IMPORTED_EVENT WHERE RUN_ID = ?", run))
                .isZero();

        jdbc.update("UPDATE IMPORT_STG_EVENT SET KIND = 'FIELD_CHANGE' WHERE RUN_ID = ? AND LEGACY_KEY = 'e2'", run);
        load(run, mapping).close();

        assertThat(loadIssues(run)).isEmpty();
        assertThat(history(number(loadedCase("c1"), "ID"))).hasSize(2);
        assertThat(count("SELECT COUNT(*) FROM IMPORT_LEDGER WHERE RUN_ID = ? AND ENTITY = 'EVENT'", run))
                .isEqualTo(2);
    }

    /** A staged event's kind, field and values. */
    private record Event(
            String kind,
            String field,
            @Nullable String oldValue,
            @Nullable String newValue) {}

    private void event(
            long run,
            String key,
            String caseKey,
            String at,
            @Nullable String who,
            @Nullable String whoName,
            Event event) {
        jdbc.update(
                INSERT_EVENT,
                run,
                key,
                "ef".repeat(32),
                caseKey,
                Timestamp.from(Instant.parse(at)),
                who,
                whoName,
                event.kind(),
                event.field(),
                event.oldValue(),
                event.newValue());
    }

    private void error(long run, Subject subject, String key) {
        issueRows.save(new ImportIssue(
                run, ImportIssue.Stage.DRY_RUN, subject, hash(key), Severity.ERROR, "AT_MISSING", null, Instant.now()));
    }

    /**
     * A case's imported history in time order: time, actor ("-" for none), kind, field, then each value as text, as
     * "#" and its length when redacted, or "-", and the run.
     */
    private List<String> history(long caseId) {
        return jdbc.query(
                "SELECT AT_TS, WHO_NAME_SNAPSHOT, KIND, FIELD_NAME, OLD_VALUE, OLD_HMAC, OLD_LENGTH, NEW_VALUE,"
                        + " NEW_HMAC, NEW_LENGTH, RUN_ID FROM CASE_IMPORTED_EVENT WHERE CASE_ID = ? ORDER BY AT_TS",
                (row, index) -> row.getTimestamp(1).toInstant() + " "
                        + (row.getString(2) == null ? "-" : row.getString(2)) + " " + row.getString(3) + " "
                        + row.getString(4) + " " + value(row, 5) + " " + value(row, 8) + " " + row.getLong(11),
                caseId);
    }

    /** The value at {@code column}, with its HMAC and length in the next two columns. */
    private static String value(ResultSet row, int column) throws SQLException {
        if (row.getString(column) != null) {
            return row.getString(column);
        }
        return row.getString(column + 1) == null ? "-" : "#" + row.getLong(column + 2);
    }
}
