// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.closure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reopen, once per vendor (WP-19 part b2): a Manager reopens a closed case with a reason, which is stored in
 * {@code CASE_REOPEN} with the new SLA cycle and the closing request, redacted in the audit and never mailed; the
 * case is open again with its next SLA cycle running from the reopen instant; the owner and the closing request's
 * requester are told once each, never the actor; a missing or over-long reason, an Analyst, a Reader, an outsider and
 * an open case are refused with nothing written; a reopened case can be requested for closure again. Rows are
 * committed and removed after each test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractCaseReopenIT {

    private static final String REASON = "Synthetic reason the case needs more work";

    @Autowired
    private ClosureService closures;

    @Autowired
    private ClosureDecisionService decisions;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> users = new ArrayList<>();
    private CaseSeed seed;
    private long team;
    private long owner;
    private long colleague;
    private long reader;
    private long approver;
    private long reopener;
    private CaseNumber number;
    private long caseId;
    private long closing;

    @BeforeEach
    void seedClosedCase() {
        seed = new CaseSeed(jdbc);
        team = seed.team();
        owner = member(TeamRole.ANALYST);
        colleague = member(TeamRole.ANALYST);
        reader = member(TeamRole.READER);
        approver = member(TeamRole.MANAGER);
        reopener = member(TeamRole.MANAGER);
        jdbc.update(
                "INSERT INTO CASE_TYPE_OUTCOME (CASE_TYPE_ID, OUTCOME, SORT_ORDER) VALUES (?, 'Resolved', 1)",
                seed.caseType());
        number = seed.caseRow(team, owner, Sensitivity.STANDARD, CaseStatus.OPEN);
        caseId = seed.caseId(number);
        Instant due = Instant.now().plus(Duration.ofDays(3));
        jdbc.update(
                "INSERT INTO CASE_SLA (CASE_ID, SLA_STATE, SLA_DUE_AT, AT_RISK_AT, SLA_CYCLE) VALUES (?, ?, ?, ?, 1)",
                caseId,
                "AT_RISK",
                Timestamp.from(due),
                Timestamp.from(due.minus(Duration.ofDays(1))));
        closing = request(colleague);
        transactions.executeWithoutResult(status -> decisions.decide(
                actor(approver, TeamRole.MANAGER), number.toString(), closing, ClosureDecision.APPROVED, null));
    }

    @AfterEach
    void removeCommittedRows() {
        for (String table : new String[] {
            "CASE_REOPEN", "CLOSURE_REQUEST", "NOTIFICATION_OUTBOX", "USER_ALERT", "AUDIT_EVENT", "CASE_SLA"
        }) {
            jdbc.update("DELETE FROM " + table + " WHERE CASE_ID = ?", caseId);
        }
        jdbc.update("DELETE FROM CASE_RECORD WHERE ID = ?", caseId);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", team);
        users.forEach(id -> jdbc.update("DELETE FROM APP_USER WHERE ID = ?", id));
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        long caseType = seed.caseType();
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE_OUTCOME WHERE CASE_TYPE_ID = ?", caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    @Test
    void reopenStoresTheReasonStartsTheNextCycleAndTellsOwnerAndRequesterOnce() {
        final Instant before = Instant.now();
        reopen(reopener, TeamRole.MANAGER, REASON);

        assertThat(jdbc.queryForMap("SELECT STATUS, CLOSED_AT FROM CASE_RECORD WHERE ID = ?", caseId))
                .containsEntry("STATUS", "OPEN")
                .containsEntry("CLOSED_AT", null);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT REASON, SLA_CYCLE, CLOSURE_REQUEST_ID, REOPENED_BY FROM CASE_REOPEN WHERE CASE_ID = ?", caseId);
        assertThat(row).containsEntry("REASON", REASON);
        assertThat(number(row, "SLA_CYCLE")).isEqualTo(2);
        assertThat(number(row, "CLOSURE_REQUEST_ID")).isEqualTo(closing);
        assertThat(number(row, "REOPENED_BY")).isEqualTo(reopener);
        Map<String, Object> sla = jdbc.queryForMap(
                "SELECT SLA_STATE, SLA_CYCLE, LAST_EVALUATED_AT FROM CASE_SLA WHERE CASE_ID = ?", caseId);
        assertThat(sla).containsEntry("SLA_STATE", "ON_TRACK").containsEntry("LAST_EVALUATED_AT", null);
        assertThat(number(sla, "SLA_CYCLE")).isEqualTo(2);
        Instant due = instant("SLA_DUE_AT");
        Instant atRisk = instant("AT_RISK_AT");
        assertThat(due).isBetween(before.plus(Duration.ofDays(29)), before.plus(Duration.ofDays(31)));
        assertThat(atRisk).isAfter(before).isBefore(due);
        assertThat(jdbc.queryForObject(
                        "SELECT NEW_VALUES_JSON FROM AUDIT_EVENT WHERE CASE_ID = ? AND ACTION = 'CASE_REOPENED'"
                                + " AND ENTITY_TYPE = 'CASE'",
                        String.class,
                        caseId))
                .contains("OPEN")
                .doesNotContain(REASON);
        assertThat(mails(owner))
                .singleElement()
                .asString()
                .contains("reopened case " + number)
                .doesNotContain(REASON);
        assertThat(mails(colleague)).singleElement().asString().contains("reopened case " + number);
        assertThat(mails(reopener)).isEmpty();
    }

    @Test
    void refusalsWriteNothing() {
        final long outsider = seed.user();
        users.add(outsider);
        refused(() -> reopen(reopener, TeamRole.MANAGER, " \r\n "), Kind.INVALID);
        refused(() -> reopen(reopener, TeamRole.MANAGER, "r".repeat(1001)), Kind.INVALID);
        refused(() -> reopen(owner, TeamRole.ANALYST, REASON), Kind.FORBIDDEN);
        refused(() -> reopen(reader, TeamRole.READER, REASON), Kind.FORBIDDEN);
        refused(() -> reopen(outsider, null, REASON), Kind.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT STATUS FROM CASE_RECORD WHERE ID = ?", String.class, caseId))
                .isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT SLA_STATE FROM CASE_SLA WHERE CASE_ID = ?", String.class, caseId))
                .isEqualTo("MET");
        assertThat(reopens()).isZero();

        reopen(reopener, TeamRole.MANAGER, REASON);
        refused(() -> reopen(reopener, TeamRole.MANAGER, REASON), Kind.FORBIDDEN);
        assertThat(reopens()).isEqualTo(1L);
    }

    @Test
    void reopenedCaseCanBeRequestedForClosureAgain() {
        reopen(reopener, TeamRole.MANAGER, REASON);

        long again = request(owner);

        assertThat(again).isNotEqualTo(closing);
        assertThat(jdbc.queryForMap("SELECT DECISION, VERSION FROM CLOSURE_REQUEST WHERE ID = ?", closing))
                .containsEntry("DECISION", "APPROVED")
                .satisfies(row -> assertThat(number(row, "VERSION")).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT STATUS FROM CASE_RECORD WHERE ID = ?", String.class, caseId))
                .isEqualTo("PENDING_CLOSURE");
    }

    private long request(long by) {
        return Objects.requireNonNull(transactions.execute(status -> closures.requestClosure(
                actor(by, TeamRole.ANALYST), number.toString(), "Resolved", "Synthetic summary", null, approver)));
    }

    private void reopen(long by, @Nullable TeamRole role, String reason) {
        transactions.executeWithoutResult(status -> decisions.reopen(actor(by, role), number.toString(), reason));
    }

    private List<String> mails(long to) {
        return jdbc.queryForList(
                "SELECT BODY_TEXT FROM NOTIFICATION_OUTBOX WHERE CASE_ID = ? AND RECIPIENT = ?"
                        + " AND TEMPLATE_ID = 'case-reopened'",
                String.class,
                caseId,
                "seed" + to + "@example.test");
    }

    private long reopens() {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT COUNT(*) FROM CASE_REOPEN WHERE CASE_ID = ?", Long.class, caseId));
    }

    private static long number(Map<String, Object> row, String column) {
        return ((Number) Objects.requireNonNull(row.get(column))).longValue();
    }

    private Instant instant(String column) {
        return Objects.requireNonNull(jdbc.queryForObject(
                        "SELECT " + column + " FROM CASE_SLA WHERE CASE_ID = ?", Timestamp.class, caseId))
                .toInstant();
    }

    private static void refused(ThrowingCallable call, Kind kind) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        DomainException.class,
                        refusal -> assertThat(refusal.kind()).isEqualTo(kind));
    }

    private Actor actor(long userId, @Nullable TeamRole role) {
        Map<Long, TeamRole> roles = role == null ? Map.of() : Map.of(team, role);
        return new Actor(new UserPermissions(userId, false, Set.of(), roles), "Synthetic Person " + userId);
    }

    private long member(TeamRole role) {
        long id = seed.user();
        users.add(id);
        seed.member(id, team, role);
        return id;
    }
}
