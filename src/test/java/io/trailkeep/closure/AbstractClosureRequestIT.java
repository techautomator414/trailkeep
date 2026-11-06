// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.closure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.casefile.CaseAssignmentService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.closure.ClosureService.Approver;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
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
 * Request Closure, once per vendor (WP-19 part a): the case moves to {@code PENDING_CLOSURE}, the request is audited
 * with the summary redacted and the approver is told without it; the approver list follows A.2 #18 (the team's
 * Managers minus the requester and the owner, else Compliance Admins, so an owner who is the team's only Manager is
 * approved by a Compliance Admin); refusals keep ADR-0005's order; a pending case cannot be reassigned; two requests
 * racing each other leave exactly one. Rows are committed and removed after each test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractClosureRequestIT {

    private static final String SUMMARY = "Synthetic resolution summary that never leaves the case";

    @Autowired
    private ClosureService closures;

    @Autowired
    private CaseAssignmentService assignments;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> users = new ArrayList<>();
    private final List<Long> teams = new ArrayList<>();
    private final List<Long> caseIds = new ArrayList<>();
    private CaseSeed seed;
    private long team;
    private long owner;
    private long reader;
    private long manager;
    private long otherManager;
    private long admin;
    private CaseNumber number;

    @BeforeEach
    void seed() {
        seed = new CaseSeed(jdbc);
        team = team();
        owner = member(team, TeamRole.ANALYST);
        reader = member(team, TeamRole.READER);
        manager = member(team, TeamRole.MANAGER);
        otherManager = member(team, TeamRole.MANAGER);
        admin = person();
        jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, 'COMPLIANCE_ADMIN')", admin);
        jdbc.update(
                "INSERT INTO CASE_TYPE_OUTCOME (CASE_TYPE_ID, OUTCOME, SORT_ORDER) VALUES (?, 'Resolved', 1)",
                seed.caseType());
        number = caseOf(team, owner);
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
        teams.forEach(id -> jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", id));
        jdbc.update("DELETE FROM APP_USER_GLOBAL_ROLE WHERE USER_ID = ?", admin);
        users.forEach(id -> jdbc.update("DELETE FROM APP_USER WHERE ID = ?", id));
        teams.forEach(id -> jdbc.update("DELETE FROM TEAM WHERE ID = ?", id));
        long caseType = seed.caseType();
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE_OUTCOME WHERE CASE_TYPE_ID = ?", caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    @Test
    void requestMovesTheCaseToPendingAuditsRedactedAndTellsTheApproverWithoutTheSummary() {
        long id = request(owner, TeamRole.ANALYST, number, "Resolved", SUMMARY, "Synthetic follow-up", manager);

        long caseId = seed.caseId(number);
        assertThat(jdbc.queryForMap("SELECT STATUS, VERSION FROM CASE_RECORD WHERE ID = ?", caseId))
                .containsEntry("STATUS", "PENDING_CLOSURE")
                .satisfies(row -> assertThat(((Number) Objects.requireNonNull(row.get("VERSION"))).intValue())
                        .isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT DECISION FROM CLOSURE_REQUEST WHERE ID = ?", String.class, id))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                        "SELECT NEW_VALUES_JSON FROM AUDIT_EVENT WHERE ACTION = 'CLOSURE_REQUESTED' AND ENTITY_ID = ?",
                        String.class,
                        id))
                .contains("Resolved", "Seed User " + manager)
                .doesNotContain(SUMMARY, "Synthetic follow-up");
        assertThat(jdbc.queryForObject(
                        "SELECT BODY_TEXT FROM NOTIFICATION_OUTBOX WHERE CASE_ID = ? AND RECIPIENT = ?",
                        String.class,
                        caseId,
                        "seed" + manager + "@example.test"))
                .contains("asked you to approve closing case " + number, "\"Resolved\"", "/cases/" + number)
                .doesNotContain(SUMMARY, "Synthetic follow-up");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM USER_ALERT WHERE USER_ID = ?", Long.class, manager))
                .isEqualTo(1L);
    }

    @Test
    void approversAreTheTeamsManagersMinusRequesterAndOwnerElseComplianceAdmins() {
        assertThat(approvers(owner, TeamRole.ANALYST, number)).containsExactlyInAnyOrder(manager, otherManager);
        assertThat(approvers(manager, TeamRole.MANAGER, number)).containsExactly(otherManager);

        // The owner is the team's only Manager: a Compliance Admin approves, never the owner.
        long soloTeam = team();
        long soloManager = member(soloTeam, TeamRole.MANAGER);
        long soloAnalyst = member(soloTeam, TeamRole.ANALYST);
        CaseNumber managersCase = caseOf(soloTeam, soloManager);
        List<Long> fromAnalyst = approvers(soloAnalyst, TeamRole.ANALYST, soloTeam, managersCase);
        assertThat(fromAnalyst).contains(admin).doesNotContain(soloManager, soloAnalyst);
        assertThat(approvers(soloManager, TeamRole.MANAGER, soloTeam, managersCase))
                .contains(admin)
                .doesNotContain(soloManager);

        refused(() -> request(owner, TeamRole.ANALYST, number, "Resolved", SUMMARY, null, owner), Kind.INVALID);
        refused(() -> request(owner, TeamRole.ANALYST, number, "Resolved", SUMMARY, null, admin), Kind.INVALID);
        refused(() -> request(manager, TeamRole.MANAGER, number, "Resolved", SUMMARY, null, manager), Kind.INVALID);
        refused(() -> ask(soloAnalyst, TeamRole.ANALYST, soloTeam, managersCase, soloManager), Kind.INVALID);
        ask(soloAnalyst, TeamRole.ANALYST, soloTeam, managersCase, admin);
    }

    @Test
    void refusalsFollowTheOrderNotFoundForbiddenInvalidAndWriteNothing() {
        final long otherTeam = team();
        final long outsider = member(otherTeam, TeamRole.ANALYST);
        refused(() -> ask(outsider, TeamRole.ANALYST, otherTeam, number, manager), Kind.NOT_FOUND);
        refused(() -> request(reader, TeamRole.READER, number, "Resolved", SUMMARY, null, manager), Kind.FORBIDDEN);
        refused(
                () -> request(owner, TeamRole.ANALYST, number, "Closed for good", SUMMARY, null, manager),
                Kind.INVALID);
        refused(() -> request(owner, TeamRole.ANALYST, number, "Resolved", " \r\n ", null, manager), Kind.INVALID);
        refused(
                () -> request(owner, TeamRole.ANALYST, number, "Resolved", "x".repeat(5001), null, manager),
                Kind.INVALID);
        refused(
                () -> request(owner, TeamRole.ANALYST, number, "Resolved", SUMMARY, "y".repeat(5001), manager),
                Kind.INVALID);
        assertThat(jdbc.queryForObject(
                        "SELECT STATUS FROM CASE_RECORD WHERE ID = ?", String.class, seed.caseId(number)))
                .isEqualTo("OPEN");
        assertThat(requestCount()).isZero();
    }

    @Test
    void pendingCaseCannotBeRequestedAgainNorReassigned() {
        request(owner, TeamRole.ANALYST, number, "Resolved", SUMMARY, null, manager);

        refused(() -> request(owner, TeamRole.ANALYST, number, "Resolved", SUMMARY, null, manager), Kind.FORBIDDEN);
        Integer version =
                jdbc.queryForObject("SELECT VERSION FROM CASE_RECORD WHERE ID = ?", Integer.class, seed.caseId(number));
        refused(
                () -> transactions.execute(status -> assignments.reassign(
                        actor(manager, TeamRole.MANAGER, team),
                        number.toString(),
                        Objects.requireNonNull(version),
                        team,
                        manager)),
                Kind.FORBIDDEN);
        assertThat(requestCount()).isEqualTo(1L);
    }

    @Test
    void twoRequestsRacingEachOtherLeaveExactlyOne() throws Exception {
        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> first = () -> race(together, owner, TeamRole.ANALYST, manager);
            Callable<String> second = () -> race(together, otherManager, TeamRole.MANAGER, manager);
            Future<String> a = pool.submit(first);
            Future<String> b = pool.submit(second);
            List<String> outcomes = List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));
            assertThat(outcomes).containsOnlyOnce("requested");
            assertThat(outcomes).anyMatch(o -> o.equals("CONFLICT") || o.equals("FORBIDDEN"));
        } finally {
            pool.shutdownNow();
        }
        assertThat(requestCount()).isEqualTo(1L);
    }

    private String race(CyclicBarrier together, long by, TeamRole role, long approver) throws Exception {
        together.await(30, TimeUnit.SECONDS);
        try {
            request(by, role, number, "Resolved", SUMMARY, null, approver);
            return "requested";
        } catch (DomainException refused) {
            return refused.kind().name();
        }
    }

    private long request(
            long by,
            TeamRole role,
            CaseNumber caseNumber,
            String outcome,
            String summary,
            @Nullable String followUps,
            long approver) {
        return Objects.requireNonNull(transactions.execute(status -> closures.requestClosure(
                actor(by, role, team), caseNumber.toString(), outcome, summary, followUps, approver)));
    }

    private void ask(long by, TeamRole role, long inTeam, CaseNumber caseNumber, long approver) {
        transactions.executeWithoutResult(status -> closures.requestClosure(
                actor(by, role, inTeam), caseNumber.toString(), "Resolved", SUMMARY, null, approver));
    }

    private List<Long> approvers(long by, TeamRole role, CaseNumber caseNumber) {
        return approvers(by, role, team, caseNumber);
    }

    private List<Long> approvers(long by, TeamRole role, long inTeam, CaseNumber caseNumber) {
        return closures.approvers(actor(by, role, inTeam), caseNumber.toString()).stream()
                .map(Approver::id)
                .toList();
    }

    private long requestCount() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM CLOSURE_REQUEST WHERE CASE_ID = ?", Long.class, seed.caseId(number)));
    }

    private static void refused(ThrowingCallable call, Kind kind) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        DomainException.class,
                        refusal -> assertThat(refusal.kind()).isEqualTo(kind));
    }

    private static Actor actor(long userId, TeamRole role, long inTeam) {
        return new Actor(
                new UserPermissions(userId, false, Set.<GlobalRole>of(), Map.of(inTeam, role)),
                "Synthetic Person " + userId);
    }

    private long person() {
        long id = seed.user();
        users.add(id);
        return id;
    }

    private long team() {
        long id = seed.team();
        teams.add(id);
        return id;
    }

    private long member(long teamId, TeamRole role) {
        long id = person();
        seed.member(id, teamId, role);
        return id;
    }

    private CaseNumber caseOf(long teamId, long ownerId) {
        CaseNumber created = seed.caseRow(teamId, ownerId, Sensitivity.STANDARD, CaseStatus.OPEN);
        long caseId = seed.caseId(created);
        caseIds.add(caseId);
        // Every case has its SLA row (a status move requires it).
        jdbc.update(
                "INSERT INTO CASE_SLA (CASE_ID, SLA_STATE, SLA_DUE_AT, AT_RISK_AT, SLA_CYCLE)"
                        + " VALUES (?, 'ON_TRACK', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)",
                caseId);
        return created;
    }
}
