// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.TrailkeepApplication;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.casefile.CaseHistoryService;
import io.trailkeep.casefile.CaseRetentionService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.HistoryKind;
import io.trailkeep.casefile.HistoryQuery;
import io.trailkeep.casefile.LegalHoldService;
import io.trailkeep.casefile.LegalHoldService.HoldChange;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.TestDatabaseUsers;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.ImportPeopleService;
import io.trailkeep.user.UserDirectoryService;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Import holds (WP-26 part ad, design §7.1 "import and hold all", §8.1 R6 9), loaded as the import user: a case on
 * legal hold in the legacy system and one past its retention at load are created on legal hold by the "Trailkeep
 * import" principal, who holds no Compliance Admin role, with a fixed reason naming the run, in the INSERT itself (the
 * import user may not update a hold); each hold is in the case's hold history and its History as one placed in the
 * app is, with the import principal as the actor and the reason redacted in the audit trail; reconcile finds no
 * difference; and when their retention passes the archive job and the purge leave them alone while archiving and
 * purging the unheld case loaded with them. The dry run raises no issue for held cases and forecasts how many the load
 * holds per reason and how many the archive job's next run takes. Each test removes what it wrote.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
abstract class AbstractImportHoldIT extends LoadSupport {

    /** Long enough ago that its case type's seven years had passed at the load. */
    private static final Instant LONG_AGO = Instant.parse("2019-01-10T09:00:00Z");

    @Autowired
    private LegalHoldService holds;

    @Autowired
    private CaseHistoryService caseHistory;

    @Autowired
    private CaseRetentionService retention;

    @Autowired
    private UserDirectoryService directory;

    @Autowired
    private TestDatabaseUsers databaseUsers;

    @Value("${app.db.purge-user}")
    private String purgeUser = "";

    private long admin;

    @BeforeEach
    void seedAComplianceAdmin() {
        admin = new CaseSeed(jdbc).user();
        users.add(admin);
        jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, ?)", admin, "COMPLIANCE_ADMIN");
    }

    @AfterEach
    void removeTheRole() {
        jdbc.update("DELETE FROM APP_USER_GLOBAL_ROLE WHERE USER_ID = ?", admin);
    }

    @Test
    void importHoldPlacedWithoutComplianceAdmin(@TempDir Path directoryPath) throws Exception {
        Path mapping = mapping(directoryPath);
        long run = signedOffRun(mapping);
        stageThree(run);

        load(run, mapping).close();

        assertThat(directory.permissionsOf(ImportPeopleService.IMPORTER_ID).has(GlobalRole.COMPLIANCE_ADMIN))
                .as("the import principal holds no Compliance Admin role")
                .isFalse();
        String legacy = "Imported: on legal hold in the legacy system (import run " + run + ")";
        String past = "Imported: past its retention period at import; review before release (import run " + run + ")";
        assertHeld("c1", legacy);
        assertHeld("c2", past);
        assertThat(held("c3")).isFalse();
        assertThat(holdColumns("c3")).containsEntry("LEGAL_HOLD_BY", null).containsEntry("LEGAL_HOLD_REASON", null);
        assertThat(count("SELECT COUNT(*) FROM LEGAL_HOLD_EVENT WHERE CASE_ID = ?", caseId("c3")))
                .isZero();

        Actor complianceAdmin = new Actor(directory.permissionsOf(admin), "Synthetic actor");
        String number = caseNumber("c2");
        List<HoldChange> changes = holds.history(complianceAdmin, number);
        assertThat(changes).hasSize(1);
        assertThat(changes.getFirst().placed()).isTrue();
        assertThat(changes.getFirst().by()).isEqualTo("Trailkeep import");
        assertThat(changes.getFirst().reason()).isEqualTo(past);
        assertThat(holds.current(complianceAdmin, number).orElseThrow().heldBy())
                .isEqualTo("Trailkeep import");
        List<CaseHistoryService.Entry> state = caseHistory
                .history(complianceAdmin, number, new HistoryQuery(HistoryKind.STATE, 1))
                .entries();
        assertThat(state).hasSize(1);
        assertThat(state.getFirst().action()).isEqualTo("LEGAL_HOLD_PLACED");
        assertThat(state.getFirst().who()).isEqualTo("Trailkeep import");
        assertThat(state.getFirst().origin()).isEqualTo(AuditOrigin.IMPORT);
        assertThat(jdbc.queryForList(
                        "SELECT ACTOR_ID FROM AUDIT_EVENT WHERE CASE_ID = ? AND ACTION = 'LEGAL_HOLD_PLACED'",
                        Long.class,
                        caseId("c2")))
                .containsExactly(ImportPeopleService.IMPORTER_ID);
        assertThat(jdbc.queryForList(
                        "SELECT NEW_VALUES_JSON FROM AUDIT_EVENT WHERE CASE_ID = ? AND ACTION = 'LEGAL_HOLD_PLACED'",
                        String.class,
                        caseId("c2")))
                .singleElement()
                .asString()
                .as("the reason is redacted in the audit trail, as every hold reason is")
                .doesNotContain("Imported:");
    }

    @Test
    void heldImportsOutliveTheirRetentionWhileTheUnheldOneIsArchivedAndPurged(@TempDir Path directoryPath)
            throws Exception {
        Path mapping = mapping(directoryPath);
        long run = signedOffRun(mapping);
        stageThree(run);
        load(run, mapping).close();

        command("reconcile", run, mapping, "--import.reports-dir=" + directoryPath)
                .close();
        assertThat(jdbc.queryForObject("SELECT STATUS FROM IMPORT_RUN WHERE ID = ?", String.class, run))
                .as("a held case is no difference")
                .isEqualTo("RECONCILED");
        assertThat(count("SELECT COUNT(*) FROM IMPORT_ISSUE WHERE RUN_ID = ? AND STAGE = 'RECONCILE'", run))
                .isZero();

        // c1 and c3 closed on the same day; the archive job runs once their seven years have passed.
        Instant due = Instant.parse("2024-03-01T10:00:00Z")
                .atZone(ZoneOffset.UTC)
                .plusYears(7)
                .plusSeconds(1)
                .toInstant();
        List<Long> loaded = List.of(caseId("c1"), caseId("c2"), caseId("c3"));
        assertThat(retention.dueForArchive(due))
                .as("held cases are never due")
                .contains(caseId("c3"))
                .doesNotContain(caseId("c1"), caseId("c2"));
        assertThat(retention.archive(loaded, due, Duration.ofDays(30)))
                .as("named to the archive itself, a held case is still left as it is")
                .isEqualTo(1);
        // The grace passes (test-only, as the schema owner), and the retention CronJob's purge runs as the purge user.
        jdbc.update(
                "UPDATE CASE_RECORD SET PURGE_AFTER = ? WHERE ID = ?",
                Timestamp.from(Instant.now().minusSeconds(60)),
                caseId("c3"));
        purge();

        assertThat(jdbc.queryForMap("SELECT STATUS, PURGED_AT FROM CASE_RECORD WHERE ID = ?", caseId("c3")))
                .containsEntry("STATUS", "ARCHIVED")
                .satisfies(row -> assertThat(row.get("PURGED_AT")).isNotNull());
        for (String key : List.of("c1", "c2")) {
            assertThat(jdbc.queryForMap(
                            "SELECT STATUS, ARCHIVED_AT, PURGED_AT, PURGE_AFTER FROM CASE_RECORD WHERE ID = ?",
                            caseId(key)))
                    .as(key + " stays closed, unarchived and whole")
                    .containsEntry("STATUS", "CLOSED")
                    .containsEntry("ARCHIVED_AT", null)
                    .containsEntry("PURGED_AT", null)
                    .containsEntry("PURGE_AFTER", null);
        }
    }

    @Test
    void dryRunRaisesNoIssueForHeldCasesAndForecastsHoldsAndTheNextArchiveRun(@TempDir Path directoryPath)
            throws Exception {
        Path mapping = mapping(directoryPath);
        long run = signedOffRun(mapping);
        // Not signed off yet: a dry run may run again until the sign-off.
        jdbc.update("UPDATE IMPORT_RUN SET APPROVED_BY = NULL, APPROVED_AT = NULL WHERE ID = ?", run);
        stageThree(run);
        // Loaded on a go-live day three years from now, c4 is not yet past its retention; at the archive job's first
        // run after it (monthly here, on the 1st) it is: the one case the load does not hold that the job takes.
        LocalDate goLive = LocalDate.now(ZoneOffset.UTC).plusYears(3).withDayOfMonth(15);
        stage(run, "c4", "CLOSED", "p1");
        closed(
                run,
                "c4",
                goLive.minusYears(7).plusDays(5).atStartOfDay(ZoneOffset.UTC).toInstant());

        dryRun(run, mapping).close();

        assertThat(dryRunIssues(run))
                .as("held cases load: no HOLD_UNAVAILABLE, nothing FATAL")
                .isEmpty();
        assertThat(figures(run))
                .contains("HOLDS LEGAL_HOLD 1", "HOLDS PAST_RETENTION 1", "ARCHIVED_NEXT_RUN - 0")
                .doesNotContain("HOLDS PAST_RETENTION 2");

        dryRun(run, mapping, "--import.go-live=" + goLive, "--app.retention.archive-cron=0 0 0 1 * *")
                .close();

        assertThat(figures(run))
                .as("numbers only: counts per reason, and the cases the archive job's next run takes")
                .contains("HOLDS LEGAL_HOLD 1", "HOLDS PAST_RETENTION 1", "ARCHIVED_NEXT_RUN - 1");
    }

    /**
     * Three closed cases: c1 on legal hold in the legacy system, c2 closed long ago and c3 closed recently, as c1 was.
     */
    private void stageThree(long run) {
        stage(run, "c1", "CLOSED", "p1");
        stage(run, "c2", "CLOSED", "p2");
        stage(run, "c3", "CLOSED", "p1");
        jdbc.update("UPDATE IMPORT_STG_CASE SET LEGAL_HOLD = ? WHERE RUN_ID = ? AND LEGACY_KEY = 'c1'", true, run);
        closed(run, "c2", LONG_AGO);
    }

    /** Moves a staged case's closing time, with its opening and identification a month before it. */
    private void closed(long run, String key, Instant closedAt) {
        Instant opened = closedAt.minus(Duration.ofDays(30));
        jdbc.update(
                "UPDATE IMPORT_STG_CASE SET CLOSED_AT = ?, OPENED_AT = ?, IDENTIFIED_ON = ?"
                        + " WHERE RUN_ID = ? AND LEGACY_KEY = ?",
                Timestamp.from(closedAt),
                Timestamp.from(opened),
                Date.valueOf(LocalDate.ofInstant(opened, ZoneOffset.UTC)),
                run,
                key);
    }

    /** A held case: placed by the principal when it was created, with the reason, in the INSERT, and its event. */
    private void assertHeld(String key, String reason) {
        assertThat(held(key)).as(key).isTrue();
        Map<String, @Nullable Object> held = holdColumns(key);
        assertThat(number(held, "LEGAL_HOLD_BY")).isEqualTo(ImportPeopleService.IMPORTER_ID);
        assertThat(held).containsEntry("LEGAL_HOLD_REASON", reason);
        assertThat(instant(held, "LEGAL_HOLD_AT")).isEqualTo(instant(held, "CREATED_AT"));
        assertThat(number(held, "VERSION"))
                .as("written held by the INSERT, never updated")
                .isZero();
        assertThat(jdbc.queryForMap(
                        "SELECT ACTION, CHANGED_BY, REASON, PURGE_AFTER FROM LEGAL_HOLD_EVENT WHERE CASE_ID = ?",
                        caseId(key)))
                .containsEntry("ACTION", "PLACED")
                .containsEntry("REASON", reason)
                .containsEntry("PURGE_AFTER", null)
                .satisfies(row -> assertThat(number(row, "CHANGED_BY")).isEqualTo(ImportPeopleService.IMPORTER_ID));
    }

    /** Whether the case is held, compared in SQL with a bound value, as each vendor stores a boolean its own way. */
    private boolean held(String key) {
        Integer held = jdbc.queryForObject(
                "SELECT COUNT(*) FROM CASE_RECORD WHERE ID = ? AND LEGAL_HOLD = ?", Integer.class, caseId(key), true);
        return held != null && held == 1;
    }

    private Map<String, @Nullable Object> holdColumns(String key) {
        return jdbc.queryForMap(
                "SELECT LEGAL_HOLD_BY, LEGAL_HOLD_AT, LEGAL_HOLD_REASON, CREATED_AT, VERSION"
                        + " FROM CASE_RECORD WHERE LEGACY_REF = ?",
                "memory1:" + key);
    }

    private long caseId(String key) {
        return number(loadedCase(key), "ID");
    }

    private String caseNumber(String key) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT CASE_NUMBER FROM CASE_RECORD WHERE LEGACY_REF = ?", String.class, "memory1:" + key));
    }

    /** As the retention CronJob starts it: the purge profile and user, the owner named only by app.db.schema. */
    private void purge() throws Exception {
        new SpringApplicationBuilder(TrailkeepApplication.class)
                .web(WebApplicationType.NONE)
                .profiles(vendorProfile(), "purge")
                .properties(
                        "spring.datasource.url=" + databaseUsers.jdbcUrl(),
                        "spring.datasource.username=" + purgeUser,
                        "spring.datasource.password=" + databaseUsers.newPassword(purgeUser),
                        "app.db.schema=" + databaseUsers.ownerSchema())
                .run()
                .close();
    }
}
