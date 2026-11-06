// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.updaterequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.casefile.CaseCommentService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.updaterequest.UpdateRequestService.OpenRequest;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Request Update, once per vendor (WP-18 part a): a request is audited with its message redacted and reaches the
 * recipient as a mail and an alert without the message; a recipient who cannot read the case, the actor themselves, a
 * disabled person and a bad message are 422, a Reader asking is 403 and an outsider 404; a second open request to the
 * same person is 409; the recipient's comment answers their open requests in the posting transaction and nobody
 * else's comment does; "Mark answered" is the requester's or a Manager's, once. Rows are committed (the comment
 * listener runs before commit) and removed after each test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractUpdateRequestIT {

    private static final String QUESTION = "Synthetic question that never leaves the case";

    @Autowired
    private UpdateRequestService requests;

    @Autowired
    private CaseCommentService comments;

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
    private long colleague;
    private long reader;
    private long manager;
    private long outsider;
    private CaseNumber number;

    @BeforeEach
    void seed() {
        seed = new CaseSeed(jdbc);
        team = team();
        owner = member(team, TeamRole.ANALYST);
        colleague = member(team, TeamRole.ANALYST);
        reader = member(team, TeamRole.READER);
        manager = member(team, TeamRole.MANAGER);
        outsider = member(team(), TeamRole.ANALYST);
        number = caseOf(Sensitivity.STANDARD);
    }

    @AfterEach
    void removeCommittedRows() {
        for (long caseId : caseIds) {
            for (String table : new String[] {
                "UPDATE_REQUEST", "NOTIFICATION_OUTBOX", "USER_ALERT", "AUDIT_EVENT", "CASE_SEARCH", "CASE_COMMENT"
            }) {
                jdbc.update("DELETE FROM " + table + " WHERE CASE_ID = ?", caseId);
            }
            jdbc.update("DELETE FROM CASE_RECORD WHERE ID = ?", caseId);
        }
        for (long id : teams) {
            jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", id);
        }
        for (long id : users) {
            jdbc.update("DELETE FROM APP_USER WHERE ID = ?", id);
        }
        for (long id : teams) {
            jdbc.update("DELETE FROM TEAM WHERE ID = ?", id);
        }
        long caseType = seed.caseType();
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    @Test
    void requestIsAuditedRedactedAndReachesTheRecipientWithoutTheMessage() {
        long id = request(owner, colleague);

        long caseId = seed.caseId(number);
        assertThat(jdbc.queryForObject(
                        "SELECT NEW_VALUES_JSON FROM AUDIT_EVENT WHERE ENTITY_TYPE = 'UPDATE_REQUEST' AND ENTITY_ID = ?"
                                + " AND ACTION = 'UPDATE_REQUESTED'",
                        String.class,
                        id))
                .contains("Seed User " + colleague)
                .doesNotContain(QUESTION);
        assertThat(jdbc.queryForMap(
                        "SELECT RECIPIENT, SUBJECT, BODY_TEXT FROM NOTIFICATION_OUTBOX WHERE CASE_ID = ?", caseId))
                .containsEntry("RECIPIENT", "seed" + colleague + "@example.test")
                .containsEntry("SUBJECT", "Update requested on " + number)
                .satisfies(mail -> assertThat(Objects.toString(mail.get("BODY_TEXT")))
                        .contains("asked you for an update on case " + number, "/cases/" + number + "#comments")
                        .doesNotContain(QUESTION));
        assertThat(jdbc.queryForObject("SELECT USER_ID FROM USER_ALERT WHERE CASE_ID = ?", Long.class, caseId))
                .isEqualTo(colleague);
        assertThat(requests.openFor(actor(colleague, TeamRole.ANALYST)))
                .singleElement()
                .satisfies(open -> assertThat(open)
                        .extracting(OpenRequest::id, OpenRequest::caseNumber, OpenRequest::caseTitle)
                        .containsExactly(id, number, "Seed case " + caseId));
        assertThat(requests.openForCase(actor(owner, TeamRole.ANALYST), number.toString()))
                .singleElement()
                .satisfies(open -> assertThat(open.message()).isEqualTo(QUESTION));
    }

    @Test
    void refusalsFollowTheOrderNotFoundForbiddenInvalid() {
        CaseNumber restricted = caseOf(Sensitivity.RESTRICTED);
        jdbc.update("UPDATE APP_USER SET DISABLED = ? WHERE ID = ?", true, manager);

        refused(() -> ask(outsider, TeamRole.ANALYST, number, colleague, QUESTION), Kind.NOT_FOUND);
        refused(() -> ask(reader, TeamRole.READER, number, colleague, QUESTION), Kind.FORBIDDEN);
        refused(() -> ask(owner, TeamRole.ANALYST, number, outsider, QUESTION), Kind.INVALID);
        refused(() -> ask(owner, TeamRole.ANALYST, restricted, colleague, QUESTION), Kind.INVALID);
        refused(() -> ask(owner, TeamRole.ANALYST, number, owner, QUESTION), Kind.INVALID);
        refused(() -> ask(owner, TeamRole.ANALYST, number, manager, QUESTION), Kind.INVALID);
        refused(() -> ask(owner, TeamRole.ANALYST, number, colleague, " \r\n "), Kind.INVALID);
        refused(() -> ask(owner, TeamRole.ANALYST, number, colleague, "x".repeat(2001)), Kind.INVALID);
        refused(() -> requests.openForCase(actor(outsider, TeamRole.ANALYST), number.toString()), Kind.NOT_FOUND);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM UPDATE_REQUEST WHERE CASE_ID = ?", Long.class, seed.caseId(number)))
                .isZero();
    }

    @Test
    void secondOpenRequestToTheSamePersonConflictsUntilTheFirstIsAnswered() {
        long first = request(owner, colleague);
        refused(() -> request(owner, colleague), Kind.CONFLICT);
        refused(() -> request(manager, colleague), Kind.CONFLICT);

        transactions.executeWithoutResult(
                status -> requests.markAnswered(actor(owner, TeamRole.ANALYST), number.toString(), first));
        assertThat(request(owner, colleague)).isNotEqualTo(first);
    }

    @Test
    void recipientsCommentAnswersTheirOpenRequestOnlyAndRollsBackWithIt() {
        long fromColleague = request(owner, colleague);
        long fromManager = request(owner, manager);

        comment(owner, TeamRole.ANALYST, "The owner's synthetic comment");
        transactions.executeWithoutResult(status -> {
            comments.post(actor(manager, TeamRole.MANAGER), number.toString(), "A rolled-back synthetic comment");
            status.setRollbackOnly();
        });
        assertThat(openIds()).containsExactlyInAnyOrder(fromColleague, fromManager);

        comment(colleague, TeamRole.ANALYST, "The colleague's synthetic answer");
        assertThat(openIds()).containsExactly(fromManager);
        assertThat(jdbc.queryForObject(
                        "SELECT ANSWERED_BY FROM UPDATE_REQUEST WHERE ID = ?", Long.class, fromColleague))
                .isEqualTo(colleague);
        assertThat(jdbc.queryForObject(
                        "SELECT ANSWERED_BY_COMMENT_ID FROM UPDATE_REQUEST WHERE ID = ? AND OPEN_KEY IS NULL",
                        Long.class,
                        fromColleague))
                .isEqualTo(
                        jdbc.queryForObject("SELECT ID FROM CASE_COMMENT WHERE AUTHOR_ID = ?", Long.class, colleague));
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM AUDIT_EVENT WHERE ACTION = 'UPDATE_ANSWERED' AND ENTITY_ID = ?",
                        Long.class,
                        fromColleague))
                .isEqualTo(1L);
    }

    @Test
    void markAnsweredIsTheRequestersOrManagersAndHappensOnce() {
        long fromColleague = request(owner, colleague);
        long fromReader = request(colleague, reader);

        refused(() -> markAnswered(colleague, TeamRole.ANALYST, fromColleague), Kind.FORBIDDEN);
        refused(() -> markAnswered(reader, TeamRole.READER, fromColleague), Kind.FORBIDDEN);
        refused(() -> markAnswered(outsider, TeamRole.ANALYST, fromColleague), Kind.NOT_FOUND);
        refused(() -> markAnswered(manager, TeamRole.MANAGER, -1), Kind.NOT_FOUND);
        markAnswered(manager, TeamRole.MANAGER, fromColleague);
        refused(() -> markAnswered(manager, TeamRole.MANAGER, fromColleague), Kind.CONFLICT);
        markAnswered(colleague, TeamRole.ANALYST, fromReader);

        assertThat(openIds()).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM AUDIT_EVENT WHERE ACTION = 'UPDATE_ANSWERED' AND CASE_ID = ?",
                        Long.class,
                        seed.caseId(number)))
                .isEqualTo(2L);
    }

    private long request(long from, long to) {
        TeamRole role = from == manager ? TeamRole.MANAGER : TeamRole.ANALYST;
        return ask(from, role, number, to, QUESTION);
    }

    private long ask(long from, TeamRole role, CaseNumber caseNumber, long to, String message) {
        return Objects.requireNonNull(transactions.execute(
                status -> requests.request(actor(from, role), caseNumber.toString(), to, message)));
    }

    private void markAnswered(long by, TeamRole role, long requestId) {
        transactions.executeWithoutResult(
                status -> requests.markAnswered(actor(by, role), number.toString(), requestId));
    }

    private void comment(long by, TeamRole role, String body) {
        transactions.executeWithoutResult(status -> comments.post(actor(by, role), number.toString(), body));
    }

    private List<Long> openIds() {
        return jdbc.queryForList(
                "SELECT ID FROM UPDATE_REQUEST WHERE CASE_ID = ? AND ANSWERED_AT IS NULL",
                Long.class,
                seed.caseId(number));
    }

    private static void refused(ThrowingCallable call, Kind kind) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        DomainException.class,
                        refusal -> assertThat(refusal.kind()).isEqualTo(kind));
    }

    private Actor actor(long userId, TeamRole role) {
        // The outsider's role is in a team that has no cases (never -1: the scope probes global roles with it).
        long inTeam = userId == outsider ? 999_999_999L : team;
        return new Actor(
                new UserPermissions(userId, false, Set.of(), Map.of(inTeam, role)), "Synthetic Person " + userId);
    }

    private long team() {
        long id = seed.team();
        teams.add(id);
        return id;
    }

    private long member(long teamId, TeamRole role) {
        long id = seed.user();
        users.add(id);
        seed.member(id, teamId, role);
        return id;
    }

    private CaseNumber caseOf(Sensitivity sensitivity) {
        CaseNumber created = seed.caseRow(team, owner, sensitivity, CaseStatus.OPEN);
        caseIds.add(seed.caseId(created));
        return created;
    }
}
