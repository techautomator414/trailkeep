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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * Closure decisions, once per vendor (WP-19 part b): approving closes the case, stamps {@code CLOSED_AT} and
 * finalises the SLA ({@code MET} or {@code BREACHED}); rejecting and requesting changes need a reason, which is
 * redacted in the audit and never mailed, and reopen the case; the requester and the owner are told, never the
 * approver; only the request's approver decides, once, and a concurrent approve and reject yield exactly one
 * decision; approver reassignment follows the request-time rule. Rows are committed and removed after each test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractClosureDecisionIT {

    private static final String REASON = "Synthetic reason naming nobody";

    @Autowired
    private ClosureService closures;

    @Autowired
    private ClosureDecisionService decisions;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> users = new ArrayList<>();
    private final List<Long> caseIds = new ArrayList<>();
    private CaseSeed seed;
    private long team;
    private long owner;
    private long reader;
    private long approver;
    private long otherManager;
    private CaseNumber number;

    @BeforeEach
    void seed() {
        seed = new CaseSeed(jdbc);
        team = seed.team();
        owner = member(TeamRole.ANALYST);
        reader = member(TeamRole.READER);
        approver = member(TeamRole.MANAGER);
        otherManager = member(TeamRole.MANAGER);
        jdbc.update(
                "INSERT INTO CASE_TYPE_OUTCOME (CASE_TYPE_ID, OUTCOME, SORT_ORDER) VALUES (?, 'Resolved', 1)",
                seed.caseType());
        number = caseDueIn(Duration.ofDays(10));
    }

    @AfterEach
    void removeCommittedRows() {
        for (long caseId : caseIds) {
            for (String table :
                    new String[] {"CLOSURE_REQUEST", "NOTIFICATION_OUTBOX", "USER_ALERT", "AUDIT_EVENT", "CASE_SLA"}) {
                jdbc.update("DELETE FROM " + table + " WHERE CASE_ID = ?", caseId);
            }
            jdbc.update("DELETE FROM CASE_RECORD WHERE ID = ?", caseId);
        }
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
    void approvalClosesTheCaseFinalisesTheSlaAndTellsTheRequester() {
        final CaseNumber late = caseDueIn(Duration.ofDays(-1));
        long id = request(number);
        decide(approver, TeamRole.MANAGER, number, id, ClosureDecision.APPROVED, null);
        decide(approver, TeamRole.MANAGER, late, request(late), ClosureDecision.APPROVED, null);

        assertThat(caseRow(number)).containsEntry("STATUS", "CLOSED").doesNotContainEntry("CLOSED_AT", null);
        assertThat(sla(number)).isEqualTo("MET");
        assertThat(sla(late)).isEqualTo("BREACHED");
        assertThat(jdbc.queryForMap("SELECT DECISION, DECIDED_BY, VERSION FROM CLOSURE_REQUEST WHERE ID = ?", id))
                .containsEntry("DECISION", "APPROVED")
                .satisfies(row -> assertThat(((Number) Objects.requireNonNull(row.get("DECIDED_BY"))).longValue())
                        .isEqualTo(approver))
                .satisfies(row -> assertThat(((Number) Objects.requireNonNull(row.get("VERSION"))).intValue())
                        .isEqualTo(1));
        assertThat(audits(number, "CLOSURE_APPROVED")).isEqualTo(1L);
        assertThat(mail(number, owner)).contains("approved the request to close case " + number);
    }

    @Test
    void rejectionAndChangesNeedReasonThatIsRedactedAndNeverMailed() {
        long first = request(number);
        refused(() -> decide(approver, TeamRole.MANAGER, number, first, ClosureDecision.REJECTED, " "), Kind.INVALID);
        refused(
                () -> decide(approver, TeamRole.MANAGER, number, first, ClosureDecision.REJECTED, "r".repeat(1001)),
                Kind.INVALID);
        assertThat(caseRow(number)).containsEntry("STATUS", "PENDING_CLOSURE");

        decide(approver, TeamRole.MANAGER, number, first, ClosureDecision.REJECTED, REASON);
        assertThat(caseRow(number)).containsEntry("STATUS", "OPEN").containsEntry("CLOSED_AT", null);
        assertThat(jdbc.queryForObject("SELECT REASON FROM CLOSURE_REQUEST WHERE ID = ?", String.class, first))
                .isEqualTo(REASON);
        assertThat(jdbc.queryForObject(
                        "SELECT NEW_VALUES_JSON FROM AUDIT_EVENT WHERE ACTION = 'CLOSURE_REJECTED' AND ENTITY_ID = ?",
                        String.class,
                        first))
                .contains("REJECTED")
                .doesNotContain(REASON);
        assertThat(mail(number, owner)).contains("rejected the request").doesNotContain(REASON);

        long second = request(number);
        decide(approver, TeamRole.MANAGER, number, second, ClosureDecision.CHANGES_REQUESTED, REASON);
        assertThat(caseRow(number)).containsEntry("STATUS", "OPEN");
        assertThat(audits(number, "CLOSURE_CHANGES_REQUESTED")).isEqualTo(1L);
    }

    @Test
    void onlyTheNamedApproverDecidesAndOnlyOnce() {
        long id = request(number);
        final long outsider = person();

        refused(
                () -> decide(otherManager, TeamRole.MANAGER, number, id, ClosureDecision.APPROVED, null),
                Kind.FORBIDDEN);
        refused(() -> decide(owner, TeamRole.ANALYST, number, id, ClosureDecision.APPROVED, null), Kind.FORBIDDEN);
        refused(() -> decide(reader, TeamRole.READER, number, id, ClosureDecision.APPROVED, null), Kind.FORBIDDEN);
        refused(() -> decide(outsider, null, number, id, ClosureDecision.APPROVED, null), Kind.NOT_FOUND);
        refused(() -> decide(approver, TeamRole.MANAGER, number, -1, ClosureDecision.APPROVED, null), Kind.NOT_FOUND);
        assertThat(caseRow(number)).containsEntry("STATUS", "PENDING_CLOSURE");

        decide(approver, TeamRole.MANAGER, number, id, ClosureDecision.APPROVED, null);
        refused(() -> decide(approver, TeamRole.MANAGER, number, id, ClosureDecision.REJECTED, REASON), Kind.FORBIDDEN);
        assertThat(audits(number, "CLOSURE_APPROVED")).isEqualTo(1L);
    }

    @Test
    void concurrentApproveAndRejectYieldOneDecision() throws Exception {
        long id = request(number);
        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> approve = pool.submit(() -> race(together, id, ClosureDecision.APPROVED, null));
            Future<String> reject = pool.submit(() -> race(together, id, ClosureDecision.REJECTED, REASON));
            List<String> outcomes = List.of(approve.get(60, TimeUnit.SECONDS), reject.get(60, TimeUnit.SECONDS));
            assertThat(outcomes).containsOnlyOnce("decided");
            assertThat(outcomes).anyMatch(o -> o.equals("CONFLICT") || o.equals("FORBIDDEN"));
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM AUDIT_EVENT WHERE CASE_ID = ? AND ACTION IN"
                                + " ('CLOSURE_APPROVED', 'CLOSURE_REJECTED')",
                        Long.class,
                        seed.caseId(number)))
                .isEqualTo(1L);
        String decision = jdbc.queryForObject("SELECT DECISION FROM CLOSURE_REQUEST WHERE ID = ?", String.class, id);
        assertThat(caseRow(number)).containsEntry("STATUS", "APPROVED".equals(decision) ? "CLOSED" : "OPEN");
    }

    @Test
    void decisionTellsTheRequesterAndTheOwnerOnceEachAndNeverTheApprover() {
        long colleague = member(TeamRole.ANALYST);
        long id = Objects.requireNonNull(transactions.execute(status -> closures.requestClosure(
                actor(colleague, TeamRole.ANALYST),
                number.toString(),
                "Resolved",
                "Synthetic summary",
                null,
                approver)));

        decide(approver, TeamRole.MANAGER, number, id, ClosureDecision.APPROVED, null);

        assertThat(mail(number, colleague)).contains("approved the request to close case " + number);
        assertThat(mail(number, owner)).contains("approved the request to close case " + number);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM USER_ALERT WHERE CASE_ID = ? AND USER_ID = ?",
                        Long.class,
                        seed.caseId(number),
                        owner))
                .isEqualTo(1L);
        // The approver had the request's own alert, and nothing for their decision.
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM USER_ALERT WHERE CASE_ID = ? AND USER_ID = ?",
                        Long.class,
                        seed.caseId(number),
                        approver))
                .isEqualTo(1L);
        assertThat(mail(number, approver)).doesNotContain("approved the request");
    }

    @Test
    void approverReassignmentFollowsTheRequestTimeRule() {
        long id = request(number);

        refused(() -> reassign(owner, TeamRole.ANALYST, id, otherManager), Kind.FORBIDDEN);
        for (long notAllowed : new long[] {approver, reader, owner}) {
            refused(() -> reassign(otherManager, TeamRole.MANAGER, id, notAllowed), Kind.INVALID);
        }
        reassign(otherManager, TeamRole.MANAGER, id, otherManager);

        assertThat(jdbc.queryForObject("SELECT APPROVER_ID FROM CLOSURE_REQUEST WHERE ID = ?", Long.class, id))
                .isEqualTo(otherManager);
        assertThat(audits(number, "APPROVER_REASSIGNED")).isEqualTo(1L);
        refused(() -> decide(approver, TeamRole.MANAGER, number, id, ClosureDecision.APPROVED, null), Kind.FORBIDDEN);
        decide(otherManager, TeamRole.MANAGER, number, id, ClosureDecision.APPROVED, null);
        assertThat(caseRow(number)).containsEntry("STATUS", "CLOSED");
    }

    @Test
    void pendingMyApprovalAndTheReassignChoicesFollowTheScopeAndTheRule() {
        long id = request(number);

        assertThat(closures.awaitingMyApproval(actor(approver, TeamRole.MANAGER)))
                .singleElement()
                .satisfies(waiting -> {
                    assertThat(waiting.id()).isEqualTo(id);
                    assertThat(waiting.caseNumber()).isEqualTo(number);
                    assertThat(waiting.outcome()).isEqualTo("Resolved");
                });
        assertThat(closures.awaitingMyApproval(actor(otherManager, TeamRole.MANAGER)))
                .isEmpty();
        // The approver's request is listed only while they read the case.
        assertThat(closures.awaitingMyApproval(actor(approver, null))).isEmpty();
        assertThat(closures.reassignChoices(actor(otherManager, TeamRole.MANAGER), number.toString(), id))
                .extracting(ClosureService.Approver::id)
                .containsExactly(otherManager);
        refused(() -> closures.reassignChoices(actor(owner, TeamRole.ANALYST), number.toString(), id), Kind.FORBIDDEN);
        refused(
                () -> closures.reassignChoices(actor(otherManager, TeamRole.MANAGER), number.toString(), id + 1),
                Kind.NOT_FOUND);
    }

    private String race(CyclicBarrier together, long id, ClosureDecision decision, @Nullable String reason)
            throws Exception {
        together.await(30, TimeUnit.SECONDS);
        try {
            decide(approver, TeamRole.MANAGER, number, id, decision, reason);
            return "decided";
        } catch (DomainException refused) {
            return refused.kind().name();
        }
    }

    private long request(CaseNumber caseNumber) {
        return Objects.requireNonNull(transactions.execute(status -> closures.requestClosure(
                actor(owner, TeamRole.ANALYST),
                caseNumber.toString(),
                "Resolved",
                "Synthetic summary",
                null,
                approver)));
    }

    private void decide(
            long by,
            @Nullable TeamRole role,
            CaseNumber caseNumber,
            long id,
            ClosureDecision decision,
            @Nullable String reason) {
        transactions.executeWithoutResult(
                status -> decisions.decide(actor(by, role), caseNumber.toString(), id, decision, reason));
    }

    private void reassign(long by, TeamRole role, long id, long to) {
        transactions.executeWithoutResult(
                status -> decisions.reassignApprover(actor(by, role), number.toString(), id, to));
    }

    private Map<String, Object> caseRow(CaseNumber caseNumber) {
        return jdbc.queryForMap("SELECT STATUS, CLOSED_AT FROM CASE_RECORD WHERE ID = ?", seed.caseId(caseNumber));
    }

    private String sla(CaseNumber caseNumber) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT SLA_STATE FROM CASE_SLA WHERE CASE_ID = ?", String.class, seed.caseId(caseNumber)));
    }

    private long audits(CaseNumber caseNumber, String action) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM AUDIT_EVENT WHERE CASE_ID = ? AND ACTION = ?",
                Long.class,
                seed.caseId(caseNumber),
                action));
    }

    private String mail(CaseNumber caseNumber, long to) {
        return String.join(
                "\n",
                jdbc.queryForList(
                        "SELECT BODY_TEXT FROM NOTIFICATION_OUTBOX WHERE CASE_ID = ? AND RECIPIENT = ?",
                        String.class,
                        seed.caseId(caseNumber),
                        "seed" + to + "@example.test"));
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

    private long person() {
        long id = seed.user();
        users.add(id);
        return id;
    }

    private long member(TeamRole role) {
        long id = person();
        seed.member(id, team, role);
        return id;
    }

    private CaseNumber caseDueIn(Duration untilDue) {
        CaseNumber created = seed.caseRow(team, owner, Sensitivity.STANDARD, CaseStatus.OPEN);
        long caseId = seed.caseId(created);
        caseIds.add(caseId);
        Instant due = Instant.now().plus(untilDue);
        jdbc.update(
                "INSERT INTO CASE_SLA (CASE_ID, SLA_STATE, SLA_DUE_AT, AT_RISK_AT, SLA_CYCLE) VALUES (?, ?, ?, ?, 1)",
                caseId,
                untilDue.isNegative() ? "OVERDUE" : "ON_TRACK",
                Timestamp.from(due),
                Timestamp.from(due.minus(Duration.ofDays(2))));
        return created;
    }
}
