// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.user.TeamRole;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code reconcile} as the import user (WP-26 part aa2, design §9): a finished load reconciles, and still does once
 * {@code relink-owners} has handed a case to its legacy owner, which is counted as relinked, never as a difference;
 * cases archived or purged since, with their children, and rows loaded before written hashes were recorded are counted
 * and skipped; a row changed behind Trailkeep's back is a difference, recorded by keyed hash and Trailkeep id only,
 * while a case edited in Trailkeep is compared with the source only; a staged row neither loaded nor listed as an
 * issue is unaccounted; once staging is purged nothing reconciles. Each run replaces its own issues and figures.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
abstract class AbstractReconcileIT extends LoadSupport {

    @Test
    void loadReconcilesAndStillDoesOnceRelinkOwnersHandedTheCaseToItsLegacyOwner(@TempDir Path directory)
            throws Exception {
        Path mapping = mapping(directory);
        long run = signedOffRun(mapping);
        stage(run, "c1", "OPEN", "p2");
        stage(run, "c2", "CLOSED", "p1");
        comment(run, "n1", "c2");
        load(run, mapping).close();

        command("reconcile", run, mapping).close();

        assertThat(status(run)).isEqualTo("RECONCILED");
        assertThat(reconcileFigures(run))
                .containsAllEntriesOf(Map.of(
                        "LOADED CASE", 2L,
                        "LOADED COMMENT", 1L,
                        "WRITTEN_MATCH CASE", 2L,
                        "WRITTEN_MATCH COMMENT", 1L,
                        "CLOSURE_MATCH CASE", 1L,
                        "VERDICT -", 1L))
                .hasSize(6);
        assertThat(reconcileIssues(run)).isEmpty();

        long legacy = signedIn("NOBODY@example.test");
        long relink = relinkRun();
        command("relink-owners", relink, mapping, "--import.apply=true").close();
        assertThat(number(loadedCase("c1"), "OWNER_ID")).isEqualTo(legacy);

        command("reconcile", run, mapping).close();

        assertThat(status(run)).isEqualTo("RECONCILED");
        assertThat(reconcileFigures(run))
                .as("the relinked case compared with the source only, and counted on its own")
                .containsEntry("RELINKED CASE", 1L)
                .containsEntry("WRITTEN_MATCH CASE", 1L)
                .containsEntry("LOADED CASE", 2L)
                .containsEntry("VERDICT -", 1L);
        assertThat(reconcileIssues(run)).isEmpty();
        assertThat(refusal(relink, mapping)).as("a relink run").isEqualTo(LoadRefusal.NOT_LOADED);
    }

    @Test
    void casesArchivedOrPurgedSinceAndTheirChildrenAreCountedAndSkippedNeverDifferences(@TempDir Path directory)
            throws Exception {
        Path mapping = mapping(directory);
        long run = signedOffRun(mapping);
        stage(run, "c1", "OPEN", "p1");
        comment(run, "n1", "c1");
        stage(run, "c2", "CLOSED", "p1");
        comment(run, "n2", "c2");
        load(run, mapping).close();
        archive(number(loadedCase("c1"), "ID"), false);
        archive(number(loadedCase("c2"), "ID"), true);

        command("reconcile", run, mapping).close();

        assertThat(status(run)).isEqualTo("RECONCILED");
        assertThat(reconcileFigures(run))
                .containsAllEntriesOf(Map.of(
                        "CASE_ARCHIVED CASE", 1L,
                        "CASE_ARCHIVED COMMENT", 1L,
                        "CASE_PURGED CASE", 1L,
                        "CASE_PURGED COMMENT", 1L,
                        "LOADED CASE", 2L,
                        "LOADED COMMENT", 2L,
                        "VERDICT -", 1L))
                .as("nothing of theirs compared")
                .hasSize(7);
        assertThat(reconcileIssues(run)).isEmpty();
    }

    @Test
    void rowChangedBehindTrailkeepsBackDiffersByKeyedHashAndIdWhileEditedCaseIsComparedWithTheSourceOnly(
            @TempDir Path directory) throws Exception {
        Path mapping = mapping(directory);
        long run = signedOffRun(mapping);
        for (String key : List.of("c1", "c2", "c3")) {
            stage(run, key, "OPEN", "p1");
        }
        comment(run, "n1", "c1");
        load(run, mapping).close();
        long comment = number(
                jdbc.queryForMap("SELECT ID FROM CASE_COMMENT WHERE CASE_ID = ?", number(loadedCase("c1"), "ID")),
                "ID");
        long tampered = number(loadedCase("c2"), "ID");
        jdbc.update("UPDATE CASE_COMMENT SET BODY = 'Synthetic tampered body' WHERE ID = ?", comment);
        jdbc.update("UPDATE CASE_RECORD SET TITLE = 'Synthetic tampered title' WHERE ID = ?", tampered);
        jdbc.update(
                "UPDATE CASE_RECORD SET TITLE = 'Synthetic edit', VERSION = VERSION + 1, UPDATED_AT = ?, UPDATED_BY = ?"
                        + " WHERE ID = ?",
                Timestamp.from(Instant.now()),
                analyst,
                number(loadedCase("c3"), "ID"));

        command("reconcile", run, mapping).close();

        assertThat(status(run)).isEqualTo("LOADED");
        assertThat(reconcileFigures(run))
                .containsEntry("WRITTEN_DIFFERS CASE", 1L)
                .containsEntry("WRITTEN_DIFFERS COMMENT", 1L)
                .containsEntry("WRITTEN_MATCH CASE", 1L)
                .containsEntry("CHANGED_IN_TRAILKEEP CASE", 1L)
                .containsEntry("VERDICT -", 0L);
        assertThat(reconcileIssues(run))
                .as("codes, keyed hashes and Trailkeep ids, never a value")
                .containsExactlyInAnyOrder(
                        "CASE ERROR WRITTEN_DIFFERS " + hash("c2") + " " + tampered,
                        "COMMENT ERROR WRITTEN_DIFFERS " + hash("n1") + " " + comment);

        // As a ledger written before V19 recorded no written hashes: counted on their own, and the issues replaced.
        jdbc.update("UPDATE IMPORT_LEDGER SET WRITTEN_HASH = NULL, CLOSURE_HASH = NULL WHERE RUN_ID = ?", run);
        command("reconcile", run, mapping).close();

        assertThat(status(run)).isEqualTo("RECONCILED");
        assertThat(reconcileFigures(run))
                .containsAllEntriesOf(Map.of(
                        "WRITTEN_HASH_MISSING CASE", 3L,
                        "WRITTEN_HASH_MISSING COMMENT", 1L,
                        "LOADED CASE", 3L,
                        "LOADED COMMENT", 1L,
                        "VERDICT -", 1L))
                .hasSize(5);
        assertThat(reconcileIssues(run)).isEmpty();
    }

    @Test
    void stagedRowNeitherLoadedNorAnIssueIsUnaccountedAndNothingReconcilesOnceStagingIsPurged(@TempDir Path directory)
            throws Exception {
        Path mapping = mapping(directory);
        long run = signedOffRun(mapping);
        stage(run, "c1", "OPEN", "p1");
        stage(run, "c2", "OPEN", "p1");
        assertThat(refusal(run, mapping)).as("not loaded yet").isEqualTo(LoadRefusal.NOT_LOADED);
        load(run, mapping).close();
        final long changed = number(loadedCase("c2"), "ID");
        // Rows staged after the load, as a bug would leave them: c8 has no issue; c9 failed, so its comment did too.
        stage(run, "c8", "OPEN", "p1");
        stage(run, "c9", "OPEN", "p1");
        comment(run, "n9", "c9");
        refused(run, "c9");
        jdbc.update(
                "UPDATE IMPORT_STG_CASE SET ROW_HASH = ? WHERE RUN_ID = ? AND LEGACY_KEY = 'c2'", "ef".repeat(32), run);

        command("reconcile", run, mapping).close();

        assertThat(status(run)).isEqualTo("LOADED");
        assertThat(reconcileFigures(run))
                .containsAllEntriesOf(Map.of(
                        "LOADED CASE", 1L,
                        "ROW_HASH_DIFFERS CASE", 1L,
                        "UNACCOUNTED CASE", 1L,
                        "FAILED CASE", 1L,
                        "PARENT_FAILED COMMENT", 1L,
                        "WRITTEN_MATCH CASE", 2L,
                        "VERDICT -", 0L))
                .hasSize(7);
        assertThat(reconcileIssues(run))
                .containsExactlyInAnyOrder(
                        "CASE ERROR ROW_HASH_DIFFERS " + hash("c2") + " " + changed,
                        "CASE ERROR UNACCOUNTED " + hash("c8") + " -");

        command("reconcile", run, mapping).close();
        assertThat(reconcileFigures(run))
                .as("its own issues hide nothing from the next reconcile")
                .containsEntry("UNACCOUNTED CASE", 1L)
                .containsEntry("FAILED CASE", 1L);
        // A resumed load drops the run's earlier LOAD issues and tries again, so c9 and its comment load too.
        load(run, mapping).close();
        assertThat(loadedCases("memory1:c8"))
                .as("nor change what a resumed load does")
                .isEqualTo(1);

        jdbc.update("UPDATE IMPORT_RUN SET STAGING_PURGED_AT = ? WHERE ID = ?", Timestamp.from(Instant.now()), run);
        command("reconcile", run, mapping).close();

        assertThat(status(run)).isEqualTo("LOADED");
        assertThat(reconcileFigures(run))
                .containsAllEntriesOf(Map.of(
                        "WRITTEN_MATCH CASE", 4L, "WRITTEN_MATCH COMMENT", 1L, "STAGING_PURGED -", 1L, "VERDICT -", 0L))
                .hasSize(4);
        assertThat(reconcileIssues(run)).isEmpty();
    }

    /** A {@code LOAD} ERROR on a staged case, as a load that refused it records. */
    private void refused(long run, String key) {
        transactions.executeWithoutResult(status -> issueRows.save(new ImportIssue(
                run,
                ImportIssue.Stage.LOAD,
                ImportIssue.Subject.CASE,
                hash(key),
                ImportIssue.Severity.ERROR,
                "CASE_REFUSED",
                null,
                Instant.now())));
    }

    private void comment(long run, String key, String caseKey) {
        jdbc.update(
                "INSERT INTO IMPORT_STG_COMMENT (RUN_ID, LEGACY_KEY, ROW_HASH, CASE_KEY, AUTHOR_KEY, WRITTEN_AT, BODY,"
                        + " BODY_FORMAT) VALUES (?, ?, ?, ?, 'p1', ?, ?, 'plain')",
                run,
                key,
                "cd".repeat(32),
                caseKey,
                Timestamp.from(Instant.parse("2024-02-01T09:00:00Z")),
                "Synthetic note " + key);
    }

    private long signedIn(String email) {
        CaseSeed seed = new CaseSeed(jdbc);
        long user = seed.user();
        users.add(user);
        jdbc.update("UPDATE APP_USER SET EMAIL = ? WHERE ID = ?", email, user);
        seed.member(user, team, TeamRole.ANALYST);
        return user;
    }

    private String status(long run) {
        return String.valueOf(jdbc.queryForMap("SELECT STATUS FROM IMPORT_RUN WHERE ID = ?", run)
                .get("STATUS"));
    }

    /** The run's reconcile figures, each "OUTCOME ENTITY" (or "-" for none) to its count. */
    private Map<String, Long> reconcileFigures(long run) {
        return jdbc
                .query(
                        "SELECT METRIC, DIMENSION, METRIC_VALUE FROM IMPORT_RUN_SUMMARY WHERE RUN_ID = ?"
                                + " AND METRIC LIKE 'RECONCILE%'",
                        (rs, index) -> Map.entry(
                                rs.getString(1).substring("RECONCILE_".length()) + " "
                                        + (rs.getString(2) == null ? "-" : rs.getString(2)),
                                rs.getLong(3)),
                        run)
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** The run's {@code RECONCILE} issues: entity, severity, code, keyed hash and Trailkeep id (or "-"). */
    private List<String> reconcileIssues(long run) {
        return jdbc.query(
                "SELECT ENTITY, SEVERITY, CODE, KEY_HASH, DETAIL FROM IMPORT_ISSUE WHERE RUN_ID = ?"
                        + " AND STAGE = 'RECONCILE'",
                (rs, index) -> String.join(
                        " ",
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getObject(5) == null ? "-" : String.valueOf(rs.getLong(5))),
                run);
    }

    /** The code a refused {@code reconcile} names. */
    private LoadRefusal refusal(long run, Path mapping) {
        Throwable failure = catchThrowable(() -> command("reconcile", run, mapping));
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof LoadRefusal.RefusedException refused) {
                return refused.refusal();
            }
        }
        throw new AssertionError("reconcile was not refused", failure);
    }
}
