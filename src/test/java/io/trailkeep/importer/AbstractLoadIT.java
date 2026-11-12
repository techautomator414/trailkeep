// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.ImportIssue.Subject;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.user.ImportPeopleService;
import io.trailkeep.user.TeamRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code load} as the import user (WP-26 part t, design §3 step 5, §8.2): a signed-off run's cases written with their
 * closures, owners as the dry run chose them and import ledger rows, a case with an ERROR skipped, a second load
 * writing nothing; each unmet condition, a changed scanning mode among them, refused with its own code before anything
 * is written, as is a clamd stream limit below the importer's cap; the run lock refusing
 * while a live load holds the run or another run of its source, and taken over once that load is stale; cases the
 * rules, the owners or the import APIs refuse at the load recorded as {@code LOAD} issues while the load goes on, and
 * tried again by the next load; and a load stopped by a case it cannot finish left {@code FAILED}, the next load
 * resuming at that case. The runs and staged rows are seeded by the schema owner; each test removes the cases the
 * load committed.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
abstract class AbstractLoadIT extends LoadSupport {

    @Test
    void loadWritesEachCaseWithItsClosureOwnerAndLedgerRowAndAgainWritesNothing(@TempDir Path directory)
            throws Exception {
        Path mapping = mapping(directory);
        long run = signedOffRun(mapping);
        stage(run, "c1", "OPEN", "p1");
        stage(run, "c2", "OPEN", "p2");
        stage(run, "c3", "CLOSED", "p2");
        stage(run, "c4", "OPEN", "p1");
        issueRows.save(new ImportIssue(
                run,
                ImportIssue.Stage.DRY_RUN,
                Subject.CASE,
                hash("c4"),
                Severity.ERROR,
                "PRIORITY_INVALID",
                null,
                Instant.now()));

        load(run, mapping).close();

        assertThat(jdbc.queryForMap("SELECT STATUS, ROWS_LOADED FROM IMPORT_RUN WHERE ID = ?", run))
                .containsEntry("STATUS", "LOADED")
                .satisfies(row -> assertThat(number(row, "ROWS_LOADED")).isEqualTo(3));
        assertThat(loadedCase("c1"))
                .containsEntry("STATUS", "OPEN")
                .containsEntry("OWNER_NAME_SNAPSHOT", null)
                .satisfies(row -> assertThat(number(row, "OWNER_ID"))
                        .as("matched and eligible")
                        .isEqualTo(analyst));
        assertThat(loadedCase("c2"))
                .containsEntry("OWNER_NAME_SNAPSHOT", "Synthetic Legacy Owner")
                .satisfies(row -> assertThat(number(row, "OWNER_ID"))
                        .as("the default owner")
                        .isEqualTo(manager));
        Map<String, @Nullable Object> closed = loadedCase("c3");
        assertThat(closed)
                .containsEntry("STATUS", "CLOSED")
                .satisfies(row -> assertThat(number(row, "OWNER_ID")).isEqualTo(ImportPeopleService.IMPORTER_ID));
        assertThat(jdbc.queryForList(
                        "SELECT OUTCOME FROM CLOSURE_REQUEST WHERE CASE_ID = ?", String.class, number(closed, "ID")))
                .containsExactly("SUBSTANTIATED");
        assertThat(loadedCases("memory1:c4"))
                .as("a case with an ERROR is skipped")
                .isZero();
        assertThat(ledger(run))
                .containsExactly(
                        "c1 OPEN " + number(loadedCase("c1"), "ID") + " -",
                        "c2 OPEN " + number(loadedCase("c2"), "ID") + " -",
                        "c3 CLOSED " + number(closed, "ID") + " closed");

        load(run, mapping).close();

        assertThat(loadedCases("memory1:%"))
                .as("a load after LOADED writes nothing the ledger holds")
                .isEqualTo(3);
        assertThat(jdbc.queryForMap("SELECT STATUS, ROWS_LOADED FROM IMPORT_RUN WHERE ID = ?", run))
                .containsEntry("STATUS", "LOADED")
                .satisfies(row -> assertThat(number(row, "ROWS_LOADED")).isEqualTo(3));
    }

    @Test
    void loadRefusesEachUnmetConditionWithItsOwnCodeBeforeWritingAnything(@TempDir Path directory) throws Exception {
        Path mapping = mapping(directory);
        long run = signedOffRun(mapping);
        stage(run, "c1", "OPEN", "p1");
        jdbc.update("UPDATE IMPORT_RUN SET APPROVED_BY = NULL, APPROVED_AT = NULL WHERE ID = ?", run);
        assertThat(refusal(run, mapping)).isEqualTo(LoadRefusal.NOT_SIGNED_OFF);
        approve(run);

        issueRows.save(new ImportIssue(
                run,
                ImportIssue.Stage.DRY_RUN,
                Subject.CASE,
                null,
                Severity.FATAL,
                "DEFAULT_OWNER_MISSING",
                team,
                Instant.now()));
        assertThat(refusal(run, mapping)).isEqualTo(LoadRefusal.FATAL_ISSUES);
        jdbc.update("DELETE FROM IMPORT_ISSUE WHERE RUN_ID = ?", run);

        String file = Files.readString(owners);
        Files.writeString(owners, file + "Seed Team " + team + "-renamed,seed" + manager + "@example.test\n");
        assertThat(refusal(run, mapping)).as("a lookup file changed").isEqualTo(LoadRefusal.MAPPING_CHANGED);
        Files.writeString(owners, file);
        String text = Files.readString(mapping);
        Files.writeString(mapping, text + "# edited after the sign-off\n");
        assertThat(refusal(run, mapping)).as("the mapping file changed").isEqualTo(LoadRefusal.MAPPING_CHANGED);
        Files.writeString(mapping, text);

        jdbc.update("UPDATE IMPORT_RUN SET STAGING_PURGED_AT = ? WHERE ID = ?", Timestamp.from(Instant.now()), run);
        assertThat(refusal(run, mapping)).isEqualTo(LoadRefusal.NOT_LOADABLE);
        jdbc.update("UPDATE IMPORT_RUN SET STAGING_PURGED_AT = NULL WHERE ID = ?", run);

        long other = extractedRun(mapping);
        loading(other, Instant.now());
        assertThat(refusal(run, mapping))
                .as("a live load holds another run of the source")
                .isEqualTo(LoadRefusal.SOURCE_LOADING);
        // That load dies: its run no longer stands in the way.
        loading(other, Instant.now().minus(LoadCommand.LEASE).minus(Duration.ofMinutes(1)));

        loading(run, Instant.now());
        assertThat(refusal(run, mapping)).as("a live load holds the run").isEqualTo(LoadRefusal.RUN_LOCKED);
        assertThat(loadedCases("memory1:%")).isZero();
        assertThat(count("SELECT COUNT(*) FROM IMPORT_LEDGER WHERE RUN_ID = ?", run))
                .isZero();

        loading(run, Instant.now().minus(LoadCommand.LEASE).minus(Duration.ofMinutes(1)));
        load(run, mapping).close();

        assertThat(jdbc.queryForObject("SELECT STATUS FROM IMPORT_RUN WHERE ID = ?", String.class, run))
                .as("a stale load's run is taken over")
                .isEqualTo("LOADED");
        assertThat(ledger(run)).hasSize(1);
    }

    @Test
    void loadRefusesChangedScanningModeAndClamdStreamLimitBelowTheCapBeforeWritingAnything(@TempDir Path directory)
            throws Exception {
        Path mapping = mapping(directory);
        long run = signedOffRun(mapping);
        stage(run, "c1", "OPEN", "p1");

        assertThat(refusal(run, mapping, "--app.attachments.scanning=external"))
                .as("the mode in force is not the one the sign-off approved")
                .isEqualTo(LoadRefusal.SCAN_MODE_CHANGED);
        jdbc.update("UPDATE IMPORT_RUN SET SCAN_MODE = NULL WHERE ID = ?", run);
        assertThat(refusal(run, mapping)).as("no mode recorded").isEqualTo(LoadRefusal.SCAN_MODE_CHANGED);
        jdbc.update("UPDATE IMPORT_RUN SET SCAN_MODE = 'CLAMD' WHERE ID = ?", run);
        for (String streamMax : List.of("24MB", "")) {
            assertThatThrownBy(() -> load(run, mapping, "--import.attachments.clamd-stream-max=" + streamMax))
                    .as("clamd's declared stream limit unset or below the importer's cap")
                    .hasMessageContaining("import.attachments.clamd-stream-max must declare");
        }
        assertThat(jdbc.queryForObject("SELECT STATUS FROM IMPORT_RUN WHERE ID = ?", String.class, run))
                .isEqualTo("DRY_RUN");
        assertThat(loadedCases("memory1:%")).isZero();

        jdbc.update("UPDATE IMPORT_RUN SET SCAN_MODE = 'EXTERNAL' WHERE ID = ?", run);
        load(run, mapping, "--app.attachments.scanning=external", "--import.attachments.clamd-stream-max=")
                .close();
        assertThat(ledger(run))
                .as("external as signed off; no clamd limit needed")
                .hasSize(1);
    }

    @Test
    void loadRecordsCasesTheRulesOrTheImportApisRefuseNowAsLoadIssuesAndGoesOn(@TempDir Path directory)
            throws Exception {
        Path mapping = mapping(directory);
        long run = signedOffRun(mapping);
        stage(run, "c1", "OPEN", "p1");
        stage(run, "c2", "CLOSED", "p2");
        stage(run, "c3", "OPEN", "p1");
        stage(run, "c4", "OPEN", "p1");
        stage(run, "c5", "OPEN", "p2");
        // Changed after the dry run: the closure API refuses c2's summary, the rules c3's priority and c4's
        // description, too long for a case under a mapping that does not continue it; c5's default owner leaves.
        jdbc.update(
                "UPDATE IMPORT_STG_CASE SET CLOSURE_SUMMARY = ? WHERE RUN_ID = ? AND LEGACY_KEY = 'c2'",
                "s".repeat(5001),
                run);
        jdbc.update("UPDATE IMPORT_STG_CASE SET PRIORITY = 'URGENTISH' WHERE RUN_ID = ? AND LEGACY_KEY = 'c3'", run);
        jdbc.update(
                "UPDATE IMPORT_STG_CASE SET DESCRIPTION = ? WHERE RUN_ID = ? AND LEGACY_KEY = 'c4'",
                "d".repeat(20_001),
                run);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE USER_ID = ?", manager);

        load(run, mapping).close();

        assertThat(jdbc.queryForMap("SELECT STATUS, ROWS_LOADED FROM IMPORT_RUN WHERE ID = ?", run))
                .containsEntry("STATUS", "LOADED")
                .satisfies(row -> assertThat(number(row, "ROWS_LOADED")).isEqualTo(1));
        assertThat(loadIssues(run))
                .containsExactly(
                        "CASE_REFUSED " + hash("c2"),
                        "DEFAULT_OWNER_MISSING " + hash("c5"),
                        "DESCRIPTION_TOO_LONG " + hash("c4"),
                        "PRIORITY_INVALID " + hash("c3"));
        assertThat(ledger(run)).hasSize(1).first().asString().startsWith("c1 ");
        assertThat(loadedCases("memory1:%"))
                .as("the refused closure took its case with it")
                .isEqualTo(1);
    }

    @Test
    void loadStoppedMidwayFailsAndLaterLoadsResumeAndRetryTheCaseItRefused(@TempDir Path directory) throws Exception {
        Path mapping = mapping(directory);
        long run = signedOffRun(mapping);
        stage(run, "c1", "OPEN", "p1");
        stage(run, "c2", "OPEN", "p2");
        // The default owner leaves the team after the sign-off, so c2 has no eligible owner at the load; and the
        // import user may not record that, so the load stops there.
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE USER_ID = ?", manager);
        jdbc.update("REVOKE INSERT ON IMPORT_ISSUE FROM " + importUser);
        try {
            assertThatThrownBy(() -> load(run, mapping))
                    .hasStackTraceContaining("Import run " + run + ": the load stopped");
        } finally {
            jdbc.update("GRANT INSERT ON IMPORT_ISSUE TO " + importUser);
        }

        assertThat(jdbc.queryForMap("SELECT STATUS, ROWS_LOADED FROM IMPORT_RUN WHERE ID = ?", run))
                .containsEntry("STATUS", "FAILED")
                .satisfies(row -> assertThat(number(row, "ROWS_LOADED")).isEqualTo(1));
        assertThat(ledger(run)).hasSize(1).first().asString().startsWith("c1 ");

        load(run, mapping).close();

        assertThat(jdbc.queryForObject("SELECT STATUS FROM IMPORT_RUN WHERE ID = ?", String.class, run))
                .isEqualTo("LOADED");
        assertThat(loadIssues(run)).containsExactly("DEFAULT_OWNER_MISSING " + hash("c2"));

        new CaseSeed(jdbc).member(manager, team, TeamRole.MANAGER);
        load(run, mapping).close();

        assertThat(jdbc.queryForMap("SELECT STATUS, ROWS_LOADED FROM IMPORT_RUN WHERE ID = ?", run))
                .containsEntry("STATUS", "LOADED")
                .satisfies(row -> assertThat(number(row, "ROWS_LOADED")).isEqualTo(2));
        assertThat(loadIssues(run))
                .as("the earlier attempt's LOAD issue went, and its case was tried again")
                .isEmpty();
        assertThat(ledger(run)).hasSize(2);
        assertThat(loadedCases("memory1:%"))
                .as("the resumed loads wrote only the case they stopped at")
                .isEqualTo(2);
    }
}
