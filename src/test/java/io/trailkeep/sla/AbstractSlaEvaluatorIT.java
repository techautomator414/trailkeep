// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The SLA evaluator on both vendors (WP-21 part a; IMPLEMENTATION_PLAN WP-21 acceptance, BUILD_PLAN §6). The fixture
 * is 20 cases whose {@code CASE_SLA} rows are inserted directly with instants on a timeline in 2001, so no row another
 * test committed (all dated around today) is ever a candidate and every count is exact. Three runs at T1, T2 and T3
 * and a fourth at T3, as a restarted replica would run it, give the expected transitions, one history event and one
 * ledger row per transition and nothing more; the case rows are never touched; a reopened case (cycle 2) goes at risk
 * again with its own ledger row; more candidates than a chunk all move. Rows are committed and removed after each
 * test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractSlaEvaluatorIT {

    private static final Instant T0 = Instant.parse("2001-01-01T00:00:00Z");
    private static final Instant T1 = T0.plus(Duration.ofDays(10));
    private static final Instant T2 = T0.plus(Duration.ofDays(20));
    private static final Instant T3 = T0.plus(Duration.ofDays(30));

    @Autowired
    private SlaEvaluator evaluator;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> users = new ArrayList<>();
    private CaseSeed seed;
    private long team;
    private long owner;

    @BeforeEach
    void seedTeam() {
        seed = new CaseSeed(jdbc);
        team = seed.team();
        owner = seed.user();
        users.add(owner);
    }

    @AfterEach
    void removeCommittedRows() {
        String ofTeam = " WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = ?)";
        for (String table : new String[] {"ALERT_LEDGER", "AUDIT_EVENT", "CASE_SLA"}) {
            jdbc.update("DELETE FROM " + table + ofTeam, team);
        }
        jdbc.update("DELETE FROM CASE_RECORD WHERE TEAM_ID = ?", team);
        users.forEach(id -> jdbc.update("DELETE FROM APP_USER WHERE ID = ?", id));
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        long caseType = seed.caseType();
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    @Test
    void twentyCasesMoveAsExpectedAcrossThreeRunsAndARestart() {
        Map<String, List<Long>> fixture = fixture();
        Map<Long, Integer> versions = versions();

        assertThat(evaluator.evaluate(T1)).isEqualTo(11);
        assertThat(states(fixture))
                .containsEntry("onTrack", List.of("ON_TRACK"))
                .containsEntry("atRiskStays", List.of("AT_RISK"))
                .containsEntry("atRiskThenOverdue", List.of("AT_RISK"))
                .containsEntry("straightToOverdue", List.of("OVERDUE"))
                .containsEntry("atRiskDueLater", List.of("AT_RISK"))
                .containsEntry("pendingClosure", List.of("AT_RISK"));
        assertThat(evaluator.evaluate(T2)).isEqualTo(4);
        assertThat(states(fixture)).containsEntry("atRiskThenOverdue", List.of("OVERDUE"));
        assertThat(evaluator.evaluate(T3)).isEqualTo(2);
        assertThat(evaluator.evaluate(T3))
                .as("a restarted replica's run at the same time")
                .isZero();

        assertThat(states(fixture))
                .containsEntry("onTrack", List.of("ON_TRACK"))
                .containsEntry("atRiskStays", List.of("AT_RISK"))
                .containsEntry("atRiskThenOverdue", List.of("OVERDUE"))
                .containsEntry("straightToOverdue", List.of("OVERDUE"))
                .containsEntry("atRiskDueLater", List.of("OVERDUE"))
                .containsEntry("met", List.of("MET"))
                .containsEntry("notApplicable", List.of("NOT_APPLICABLE"))
                .containsEntry("pendingClosure", List.of("AT_RISK"));
        assertThat(ledger("SLA_AT_RISK")).isEqualTo(9);
        assertThat(ledger("SLA_OVERDUE")).isEqualTo(8);
        assertThat(count("SELECT COUNT(*) FROM AUDIT_EVENT WHERE ACTION = 'SLA_STATE_CHANGED' AND ORIGIN = 'SCHEDULER'"
                        + " AND CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = ?)"))
                .isEqualTo(17);
        assertThat(versions()).as("the evaluator never touches the case row").isEqualTo(versions);
        assertThat(jdbc.queryForObject(
                        "SELECT LAST_ITEMS FROM JOB_RUN WHERE JOB_NAME = ?", Integer.class, SlaEvaluator.JOB))
                .isZero();
        Long evaluated = count("SELECT COUNT(*) FROM CASE_SLA WHERE LAST_EVALUATED_AT IS NOT NULL"
                + " AND CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = ?)");
        assertThat(evaluated).as("only the 13 cases that moved").isEqualTo(13);
    }

    @Test
    void reopenedCaseGoesAtRiskAgainInItsNewCycle() {
        long caseId = sla(CaseStatus.OPEN, "ON_TRACK", 1, T0.plus(Duration.ofDays(5)), T0.plus(Duration.ofDays(50)));
        assertThat(evaluator.evaluate(T1)).isOne();
        // A reopen, as CaseSla.restart writes it: the next cycle, on track, with new instants.
        jdbc.update(
                "UPDATE CASE_SLA SET SLA_STATE = 'ON_TRACK', SLA_CYCLE = 2, AT_RISK_AT = ?, SLA_DUE_AT = ?,"
                        + " LAST_EVALUATED_AT = NULL WHERE CASE_ID = ?",
                Timestamp.from(T0.plus(Duration.ofDays(15))),
                Timestamp.from(T0.plus(Duration.ofDays(60))),
                caseId);

        assertThat(evaluator.evaluate(T2)).isOne();
        assertThat(evaluator.evaluate(T2)).isZero();
        assertThat(jdbc.queryForList(
                        "SELECT SLA_CYCLE FROM ALERT_LEDGER WHERE CASE_ID = ? AND ALERT_TYPE = 'SLA_AT_RISK'"
                                + " ORDER BY SLA_CYCLE",
                        Integer.class,
                        caseId))
                .containsExactly(1, 2);
    }

    @Test
    void moreCandidatesThanAChunkAllMove() {
        int cases = SlaEvaluator.CHUNK * 2 + 1;
        for (int i = 0; i < cases; i++) {
            sla(CaseStatus.OPEN, "ON_TRACK", 1, T0, T3.plus(Duration.ofDays(10)));
        }

        assertThat(evaluator.evaluate(T1)).isEqualTo(cases);
        assertThat(ledger("SLA_AT_RISK")).isEqualTo(cases);
        assertThat(evaluator.evaluate(T1)).isZero();
    }

    /** The 20 cases, by what the timeline does to them. */
    private Map<String, List<Long>> fixture() {
        Map<String, List<Long>> fixture = new LinkedHashMap<>();
        Instant far = T3.plus(Duration.ofDays(100));
        add(fixture, "onTrack", 4, CaseStatus.OPEN, "ON_TRACK", T3.plus(Duration.ofDays(50)), far);
        add(fixture, "atRiskStays", 4, CaseStatus.OPEN, "ON_TRACK", T0.plus(Duration.ofDays(5)), far);
        add(fixture, "atRiskThenOverdue", 4, CaseStatus.OPEN, "ON_TRACK", T0, T1.plus(Duration.ofDays(5)));
        add(fixture, "straightToOverdue", 2, CaseStatus.OPEN, "ON_TRACK", T0, T0.plus(Duration.ofDays(2)));
        add(fixture, "atRiskDueLater", 2, CaseStatus.OPEN, "AT_RISK", T0, T2.plus(Duration.ofDays(5)));
        add(fixture, "met", 2, CaseStatus.CLOSED, "MET", T0, T0.plus(Duration.ofDays(1)));
        add(fixture, "notApplicable", 1, CaseStatus.CLOSED, "NOT_APPLICABLE", null, null);
        // Pending closure does not pause the clock (BUILD_PLAN §6).
        add(fixture, "pendingClosure", 1, CaseStatus.PENDING_CLOSURE, "ON_TRACK", T0, far);
        assertThat(fixture.values().stream().mapToInt(List::size).sum()).isEqualTo(20);
        return fixture;
    }

    private void add(
            Map<String, List<Long>> fixture,
            String group,
            int size,
            CaseStatus status,
            String state,
            @Nullable Instant atRisk,
            @Nullable Instant due) {
        List<Long> ids = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ids.add(sla(status, state, 1, atRisk, due));
        }
        fixture.put(group, ids);
    }

    /** A seeded case and its SLA row, inserted directly. */
    private long sla(CaseStatus status, String state, int cycle, @Nullable Instant atRisk, @Nullable Instant due) {
        CaseNumber number = seed.caseRow(team, owner, Sensitivity.STANDARD, status);
        long caseId = seed.caseId(number);
        jdbc.update(
                "INSERT INTO CASE_SLA (CASE_ID, SLA_STATE, SLA_DUE_AT, AT_RISK_AT, SLA_CYCLE) VALUES (?, ?, ?, ?, ?)",
                caseId,
                state,
                due == null ? null : Timestamp.from(due),
                atRisk == null ? null : Timestamp.from(atRisk),
                cycle);
        return caseId;
    }

    /** Each group's distinct states. */
    private Map<String, List<String>> states(Map<String, List<Long>> fixture) {
        Map<String, List<String>> states = new LinkedHashMap<>();
        fixture.forEach((group, ids) -> states.put(
                group,
                ids.stream()
                        .map(id -> jdbc.queryForObject(
                                "SELECT SLA_STATE FROM CASE_SLA WHERE CASE_ID = ?", String.class, id))
                        .distinct()
                        .toList()));
        return states;
    }

    private Map<Long, Integer> versions() {
        Map<Long, Integer> versions = new LinkedHashMap<>();
        jdbc.query(
                "SELECT ID, VERSION FROM CASE_RECORD WHERE TEAM_ID = ? ORDER BY ID",
                row -> {
                    versions.put(row.getLong("ID"), row.getInt("VERSION"));
                },
                team);
        return versions;
    }

    private long ledger(String type) {
        return count("SELECT COUNT(*) FROM ALERT_LEDGER WHERE ALERT_TYPE = '" + type + "'"
                + " AND CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = ?)");
    }

    private Long count(String sql) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, team));
    }
}
