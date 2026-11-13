// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.TeamRole;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The reminders tick on both vendors (WP-17b; BUILD_PLAN §6 "Reminders tick"). On a timeline in 2001 (so no row
 * another test committed is ever due): an open update request and a pending closure request made at T0, and a case
 * overdue since eight days before T0 under the default 7-day repeat. Ticks at T1 (T0 + 4 days), twice an hour later
 * (the same day, the second as a restarted replica would), a day later and three days later give exactly one reminder
 * per period: none on the same day, none the next day (the 3-day and 7-day periods have not passed), and the next one
 * at T1 + 3 days. An answered request, a decided closure and a closed (breached) case get none. Rows are committed and
 * removed after each test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractRemindersTickIT {

    private static final Instant T0 = Instant.parse("2001-01-01T09:00:00Z");
    private static final Instant T1 = T0.plus(Duration.ofDays(4));

    @Autowired
    private RemindersTick tick;

    @Autowired
    private RemindersTickHealthIndicator health;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GoLiveAlertService goLiveAlerts;

    @Autowired
    private TransactionTemplate transactions;

    private final List<Long> users = new ArrayList<>();
    private CaseSeed seed;
    private long team;
    private long owner;
    private long requester;
    private long recipient;
    private long approver;
    private long open;
    private long pending;
    private long overdue;

    @BeforeEach
    void seedFixture() {
        seed = new CaseSeed(jdbc);
        team = seed.team();
        owner = member(TeamRole.ANALYST);
        requester = member(TeamRole.ANALYST);
        recipient = member(TeamRole.READER);
        approver = member(TeamRole.MANAGER);
        member(TeamRole.MANAGER);
        open = caseOf(CaseStatus.OPEN, "ON_TRACK", T0.plus(Duration.ofDays(50)));
        updateRequest(open, 1, null);
        updateRequest(open, 2, T0.plus(Duration.ofDays(1)));
        pending = caseOf(CaseStatus.PENDING_CLOSURE, "ON_TRACK", T0.plus(Duration.ofDays(50)));
        closureRequest(pending, 1, "PENDING");
        long closed = caseOf(CaseStatus.CLOSED, "MET", T0.plus(Duration.ofDays(50)));
        closureRequest(closed, 2, "APPROVED");
        overdue = caseOf(CaseStatus.OPEN, "OVERDUE", T0.minus(Duration.ofDays(8)));
        caseOf(CaseStatus.CLOSED, "BREACHED", T0.minus(Duration.ofDays(8)));
    }

    @AfterEach
    void removeCommittedRows() {
        String ofTeam = " WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = ?)";
        for (String table : new String[] {
            "ALERT_LEDGER", "NOTIFICATION_OUTBOX", "USER_ALERT", "UPDATE_REQUEST", "CLOSURE_REQUEST", "CASE_SLA"
        }) {
            jdbc.update("DELETE FROM " + table + ofTeam, team);
        }
        // Other tests read the aggregate health, which a stale row would take down.
        jdbc.update("DELETE FROM JOB_RUN WHERE JOB_NAME = ?", RemindersTick.JOB);
        jdbc.update("DELETE FROM CASE_RECORD WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", team);
        users.forEach(id -> jdbc.update("DELETE FROM APP_USER WHERE ID = ?", id));
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        long caseType = seed.caseType();
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    @Test
    void exactlyOneReminderPerPeriodAcrossTicksAndARestart() {
        // The update request, the closure request, and the overdue case's owner and two Managers.
        assertThat(tick.run(T1)).isEqualTo(1 + 1 + 3);
        assertThat(tick.run(T1.plus(Duration.ofHours(1)))).as("the same day").isZero();
        assertThat(tick.run(T1.plus(Duration.ofHours(1))))
                .as("a restarted replica, the same day")
                .isZero();
        assertThat(tick.run(T1.plus(Duration.ofDays(1))))
                .as("before the next period")
                .isZero();
        assertThat(tick.run(T1.plus(Duration.ofDays(3)))).isEqualTo(1 + 1 + 3);

        assertThat(mails(open, "update-reminder", recipient)).isEqualTo(2);
        assertThat(mails(pending, "closure-reminder", approver)).isEqualTo(2);
        assertThat(mails(overdue, "sla-overdue-repeat", owner)).isEqualTo(2);
        assertThat(count(
                        "SELECT COUNT(*) FROM NOTIFICATION_OUTBOX WHERE TEMPLATE_ID = 'sla-overdue-repeat'"
                                + " AND CASE_ID = ?",
                        overdue))
                .isEqualTo(6);
        assertThat(count("SELECT COUNT(*) FROM NOTIFICATION_OUTBOX" + ofTeam(), team))
                .as("nothing for the answered request, the decided closure or the closed case")
                .isEqualTo(10);
        assertThat(count("SELECT COUNT(*) FROM USER_ALERT" + ofTeam(), team)).isEqualTo(10);
        assertThat(jdbc.queryForList(
                        "SELECT PERIOD_KEY FROM ALERT_LEDGER WHERE CASE_ID = ? ORDER BY PERIOD_KEY",
                        String.class,
                        overdue))
                .containsExactly("P1", "P2");
        assertThat(count(
                        "SELECT COUNT(*) FROM ALERT_LEDGER WHERE ALERT_TYPE = 'UPDATE_REMINDER' AND CASE_ID = ?", open))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT LAST_REMINDED_AT FROM UPDATE_REQUEST WHERE ID = ?", Timestamp.class, open * 10 + 1))
                .isEqualTo(Timestamp.from(T1.plus(Duration.ofDays(3))));
    }

    /**
     * Go-live pre-marking (WP-26 part ac, design §8.1 relaxation 10): an imported case's alerts already raised are
     * recorded as sent with the keys the evaluator and the tick use, so the tick sends no repeat for the period the
     * load fell in, and the next period alerts as for any case.
     */
    @Test
    void goLiveAlertsPreMarked() {
        long atRisk = caseOf(CaseStatus.OPEN, "AT_RISK", T1.plus(Duration.ofDays(2)));

        int marked = Objects.requireNonNull(transactions.execute(status ->
                goLiveAlerts.preMark(overdue, T1) + goLiveAlerts.preMark(atRisk, T1) + goLiveAlerts.preMark(open, T1)));

        assertThat(marked).as("nothing for the on-track case").isEqualTo(3);
        assertThat(ledger(overdue)).containsExactly("SLA_OVERDUE CYCLE", "SLA_OVERDUE_REPEAT P1");
        assertThat(ledger(atRisk)).containsExactly("SLA_AT_RISK CYCLE");
        assertThat(tick.run(T1))
                .as("the two request reminders, no repeat for the pre-marked period")
                .isEqualTo(1 + 1);
        assertThat(tick.run(T1.plus(Duration.ofDays(3)))).isEqualTo(1 + 1 + 3);
        assertThat(mails(overdue, "sla-overdue-repeat", owner)).isEqualTo(1);
    }

    private List<String> ledger(long caseId) {
        return jdbc.queryForList(
                "SELECT ALERT_TYPE || ' ' || PERIOD_KEY FROM ALERT_LEDGER WHERE CASE_ID = ? ORDER BY ALERT_TYPE",
                String.class,
                caseId);
    }

    @Test
    void healthFollowsTheLastSuccessInJobRun() {
        jdbc.update("DELETE FROM JOB_RUN WHERE JOB_NAME = ?", RemindersTick.JOB);
        assertThat(health.health().getStatus()).isEqualTo(Status.UNKNOWN);
        tick.run(T1);
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        jdbc.update(
                "UPDATE JOB_RUN SET LAST_SUCCESS_AT = ? WHERE JOB_NAME = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(3))),
                RemindersTick.JOB);
        assertThat(health.health().getStatus())
                .as("stale past the 2-hour grace")
                .isEqualTo(Status.DOWN);
    }

    private long member(TeamRole role) {
        long id = seed.user();
        users.add(id);
        seed.member(id, team, role);
        return id;
    }

    /** A case owned by the owner, with its SLA row due at {@code due}. */
    private long caseOf(CaseStatus status, String state, Instant due) {
        long caseId = seed.caseId(seed.caseRow(team, owner, Sensitivity.STANDARD, status));
        jdbc.update(
                "INSERT INTO CASE_SLA (CASE_ID, SLA_STATE, SLA_DUE_AT, AT_RISK_AT, SLA_CYCLE) VALUES (?, ?, ?, ?, 1)",
                caseId,
                state,
                Timestamp.from(due),
                Timestamp.from(due.minus(Duration.ofDays(5))));
        return caseId;
    }

    /** An update request from the requester to the recipient, made at T0; answered at {@code answeredAt} if given. */
    private void updateRequest(long caseId, int n, @Nullable Instant answeredAt) {
        jdbc.update(
                "INSERT INTO UPDATE_REQUEST (ID, CASE_ID, REQUESTED_BY, REQUESTED_FROM, MESSAGE, REQUESTED_AT,"
                        + " ANSWERED_AT, ANSWERED_BY, OPEN_KEY) VALUES (?, ?, ?, ?, 'Synthetic message', ?, ?, ?, ?)",
                caseId * 10 + n,
                caseId,
                requester,
                recipient,
                Timestamp.from(T0),
                answeredAt == null ? null : Timestamp.from(answeredAt),
                answeredAt == null ? null : recipient,
                answeredAt == null ? caseId + ":" + recipient : null);
    }

    /** A closure request from the requester to the approver, made at T0, pending or approved. */
    private void closureRequest(long caseId, int n, String decision) {
        boolean decided = !"PENDING".equals(decision);
        jdbc.update(
                "INSERT INTO CLOSURE_REQUEST (ID, CASE_ID, REQUESTED_BY, APPROVER_ID, REQUESTED_AT, OUTCOME,"
                        + " RESOLUTION_SUMMARY, DECISION, DECIDED_BY, DECIDED_AT)"
                        + " VALUES (?, ?, ?, ?, ?, 'Resolved', 'Synthetic summary', ?, ?, ?)",
                caseId * 10 + n,
                caseId,
                requester,
                approver,
                Timestamp.from(T0),
                decision,
                decided ? approver : null,
                decided ? Timestamp.from(T0.plus(Duration.ofDays(1))) : null);
    }

    private long mails(long caseId, String template, long to) {
        return count(
                "SELECT COUNT(*) FROM NOTIFICATION_OUTBOX WHERE CASE_ID = ? AND TEMPLATE_ID = '" + template + "'"
                        + " AND RECIPIENT = 'seed" + to + "@example.test'",
                caseId);
    }

    private static String ofTeam() {
        return " WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = ?)";
    }

    private long count(String sql, long arg) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, arg));
    }
}
