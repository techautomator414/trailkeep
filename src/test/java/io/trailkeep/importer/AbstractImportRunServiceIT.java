// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.importer.ImportRunService.Figure;
import io.trailkeep.importer.ImportRunService.IssueCount;
import io.trailkeep.importer.ImportRunService.RunDetail;
import io.trailkeep.importer.ImportRunService.RunSummary;
import io.trailkeep.shared.DomainException;
import io.trailkeep.shared.Paged;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The importer's read API, once per vendor (WP-25 part d): no run reads as empty; the latest run is the one requested
 * last, and between two requested at the same instant the one with the larger id; its kind and status come back as
 * codes, and its loaded count only once loading has started; a signed-off dry run reads as approved. For the Import
 * runs screen (WP-26 part s1): runs come 20 to a page, newest first, and one run with its issues per class (fatal
 * first) and its figures (per team with the team id, without the issue counts, the forecast day as a date); only System
 * and Compliance Admins may read them, and an unknown run is not found. The tables are emptied and filled inside the
 * test's transaction, which is rolled back.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class AbstractImportRunServiceIT {

    private static final Instant T = Instant.parse("2026-11-11T09:00:00Z");

    @Autowired
    private ImportRunService imports;

    @Autowired
    private JdbcTemplate jdbc;

    private long requester;

    @BeforeEach
    void emptyTheRuns() {
        // Every table that references IMPORT_RUN first, children before parents, so runs other importer ITs left in
        // the shared test database never block the delete.
        for (String table : List.of(
                "IMPORT_LEDGER",
                "CASE_IMPORTED_EVENT",
                "IMPORT_STG_EVENT",
                "IMPORT_STG_ATTACHMENT",
                "IMPORT_STG_COMMENT",
                "IMPORT_STG_CASE",
                "IMPORT_STG_PERSON",
                "IMPORT_RUN_CHECKPOINT",
                "IMPORT_ISSUE",
                "IMPORT_RUN_SUMMARY",
                "IMPORT_RUN")) {
            // Test-only cleanup over the fixed table names of this list.
            jdbc.update("DELETE FROM " + table);
        }
        requester = new CaseSeed(jdbc).user();
    }

    @Test
    void noRunReadsAsEmpty() {
        assertThat(imports.latest()).isEmpty();
    }

    @Test
    void theLatestIsRequestedLastThenHasTheLargerId() {
        run(989_003L, "RELINK", "LOADED", T.minusSeconds(3_600), 40);
        run(989_001L, "IMPORT", "DRY_RUN", T, 0);
        run(989_002L, "IMPORT", "LOADING", T, 7);

        assertThat(imports.latest())
                .contains(new ImportRunService.RunSummary(989_002L, "IMPORT", "LOADING", T, T.plusSeconds(60), 7L));
    }

    @Test
    void theLoadedCountIsLeftOutBeforeLoadingStarts() {
        run(989_004L, "IMPORT", "APPROVED", T, 0);

        assertThat(imports.latest())
                .contains(new ImportRunService.RunSummary(989_004L, "IMPORT", "APPROVED", T, T.plusSeconds(60), null));
    }

    @Test
    void signedOffDryRunReadsAsApproved() {
        run(989_005L, "IMPORT", "DRY_RUN", T, 0);
        jdbc.update(
                "UPDATE IMPORT_RUN SET APPROVED_BY = ?, APPROVED_AT = ? WHERE ID = 989005",
                requester,
                Timestamp.from(T));

        assertThat(imports.latest()).map(RunSummary::status).contains("APPROVED");
    }

    @Test
    void runsComeTwentyToAPageNewestFirst() {
        for (int i = 0; i < 21; i++) {
            run(989_100L + i, "IMPORT", "REQUESTED", T.plusSeconds(i), 0);
        }

        Paged<RunSummary> first = imports.runs(admin(GlobalRole.SYSTEM_ADMIN), 1);
        Paged<RunSummary> past = imports.runs(admin(GlobalRole.COMPLIANCE_ADMIN), 9);

        assertThat(first.total()).isEqualTo(21);
        assertThat(first.pages()).isEqualTo(2);
        assertThat(first.rows()).hasSize(20).first().extracting(RunSummary::id).isEqualTo(989_120L);
        assertThat(past.page()).isEqualTo(2);
        assertThat(past.rows()).extracting(RunSummary::id).containsExactly(989_100L);
    }

    @Test
    void oneRunComesWithItsIssuesPerClassAndItsFigures() {
        run(989_006L, "IMPORT", "DRY_RUN", T, 0);
        jdbc.update("UPDATE IMPORT_RUN SET SCAN_MODE = 'EXTERNAL', ROWS_STAGED = 12 WHERE ID = 989006");
        issue(1, "DRY_RUN", "ATTACHMENT", "WARNING", "NOT_SCANNED");
        issue(2, "EXTRACT", "CASE", "ERROR", "TITLE_MISSING");
        issue(3, "DRY_RUN", "CASE", "ERROR", "TITLE_MISSING");
        issue(4, "DRY_RUN", "MAPPING", "FATAL", "DEFAULT_OWNER_MISSING");
        figure(1, "CASES", "OPEN", 3);
        figure(2, "VISIBILITY_STANDARD", "77", 2);
        figure(3, "ISSUES", "CASE/ERROR/TITLE_MISSING", 2);
        figure(4, "SLA_FORECAST_DAY", null, 20_454);
        figure(5, "ATTACHMENT_FILES", null, 1);

        RunDetail run = imports.run(admin(GlobalRole.COMPLIANCE_ADMIN), 989_006L);

        assertThat(run.summary().status()).isEqualTo("DRY_RUN");
        assertThat(run.requestedBy()).isEqualTo(requester);
        assertThat(run.approvedBy()).isNull();
        assertThat(run.scanMode()).isEqualTo("EXTERNAL");
        assertThat(run.rowsStaged()).isEqualTo(12);
        assertThat(run.issues())
                .containsExactly(
                        new IssueCount("MAPPING", "FATAL", "DEFAULT_OWNER_MISSING", 1),
                        new IssueCount("CASE", "ERROR", "TITLE_MISSING", 2),
                        new IssueCount("ATTACHMENT", "WARNING", "NOT_SCANNED", 1));
        assertThat(run.figures())
                .containsExactly(
                        new Figure("ATTACHMENT_FILES", null, null, 1),
                        new Figure("CASES", "OPEN", null, 3),
                        new Figure("VISIBILITY_STANDARD", "77", 77L, 2));
        assertThat(run.forecastDay()).isEqualTo(LocalDate.ofEpochDay(20_454));
    }

    @Test
    void onlySystemAndComplianceAdminsReadRunsAndAnUnknownOneIsNotFound() {
        run(989_007L, "IMPORT", "REQUESTED", T, 0);
        Actor manager = new Actor(
                new UserPermissions(requester, false, Set.of(), Map.of(1L, TeamRole.MANAGER)), "Synthetic Manager");

        assertThatThrownBy(() -> imports.runs(manager, 1))
                .isInstanceOfSatisfying(
                        ImportRunException.class, e -> assertThat(e.kind()).isEqualTo(DomainException.Kind.FORBIDDEN));
        assertThatThrownBy(() -> imports.run(manager, 989_007L))
                .isInstanceOfSatisfying(
                        ImportRunException.class, e -> assertThat(e.kind()).isEqualTo(DomainException.Kind.FORBIDDEN));
        assertThatThrownBy(() -> imports.run(admin(GlobalRole.SYSTEM_ADMIN), 989_999L))
                .isInstanceOfSatisfying(
                        ImportRunException.class, e -> assertThat(e.kind()).isEqualTo(DomainException.Kind.NOT_FOUND));
        assertThat(imports.run(admin(GlobalRole.SYSTEM_ADMIN), 989_007L).issues())
                .isEmpty();
    }

    private Actor admin(GlobalRole role) {
        return new Actor(new UserPermissions(requester, false, Set.of(role), Map.of()), "Synthetic Admin");
    }

    private void issue(long id, String stage, String entity, String severity, String code) {
        jdbc.update(
                "INSERT INTO IMPORT_ISSUE (ID, RUN_ID, STAGE, ENTITY, KEY_HASH, SEVERITY, CODE, CREATED_AT)"
                        + " VALUES (?, 989006, ?, ?, ?, ?, ?, ?)",
                989_000L + id,
                stage,
                entity,
                "MAPPING".equals(entity) ? null : "cd".repeat(31) + id + id,
                severity,
                code,
                Timestamp.from(T));
    }

    private void figure(long id, String metric, @Nullable String dimension, long value) {
        jdbc.update(
                "INSERT INTO IMPORT_RUN_SUMMARY (ID, RUN_ID, METRIC, DIMENSION, METRIC_VALUE)"
                        + " VALUES (?, 989006, ?, ?, ?)",
                989_000L + id,
                metric,
                dimension,
                value);
    }

    private void run(long id, String kind, String status, Instant requestedAt, long rowsLoaded) {
        jdbc.update(
                "INSERT INTO IMPORT_RUN (ID, KIND, SOURCE_ID, STATUS, REQUESTED_BY, REQUESTED_AT, ROWS_LOADED,"
                        + " UPDATED_AT) VALUES (?, ?, 'synthetic-a', ?, ?, ?, ?, ?)",
                id,
                kind,
                status,
                requester,
                Timestamp.from(requestedAt),
                rowsLoaded,
                Timestamp.from(requestedAt.plusSeconds(60)));
    }
}
