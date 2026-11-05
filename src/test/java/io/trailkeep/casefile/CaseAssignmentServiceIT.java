// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.casefile.CaseAssignmentService.Reassignment;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.CaseService.TeamChoice;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reassign and restrict on PostgreSQL (WP-12): the audit diffs hold only what changed; the owner must work in the
 * team; a restricting Analyst-owner keeps reading the case while another Analyst of the team no longer finds it;
 * Mark standard brings it back; optimistic locking holds before and at flush; nothing is said about an unreadable
 * case before "not found".
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class CaseAssignmentServiceIT {

    @Autowired
    private CaseAssignmentService assignments;

    @Autowired
    private CaseService cases;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private CaseSeed seed;
    private long team;
    private long analyst;
    private long colleague;
    private long manager;
    private long reader;
    private CaseNumber number;

    @BeforeEach
    void seed() {
        seed = new CaseSeed(jdbc);
        team = seed.team();
        analyst = seed.user();
        colleague = seed.user();
        manager = seed.user();
        reader = seed.user();
        seed.member(analyst, team, TeamRole.ANALYST);
        seed.member(colleague, team, TeamRole.ANALYST);
        seed.member(manager, team, TeamRole.MANAGER);
        seed.member(reader, team, TeamRole.READER);
        number = cases.create(actor(analyst, TeamRole.ANALYST), fields());
        entityManager.flush();
    }

    @Test
    void reassignmentsAuditOnlyTheNewOwner() {
        Reassignment form = assignments.reassignment(actor(analyst, TeamRole.ANALYST), number.toString());
        assertThat(form.teamId()).isEqualTo(team);
        assertThat(form.ownerId()).isEqualTo(analyst);
        assertThat(form.teams()).extracting(TeamChoice::id).containsExactly(team);
        assertThat(form.teams().getFirst().owners())
                .extracting(CaseService.Choice::id)
                .contains(analyst, colleague, manager)
                .doesNotContain(reader);

        boolean stillReadable =
                assignments.reassign(actor(analyst, TeamRole.ANALYST), number.toString(), 0, team, colleague);
        entityManager.flush();

        assertThat(stillReadable).isTrue();
        Map<String, Object> event = event("CASE_REASSIGNED");
        assertThat(event).containsEntry("ACTOR_ID", analyst);
        assertThat((String) event.get("OLD_VALUES_JSON"))
                .contains("\"ownerId\":" + analyst)
                .doesNotContain("teamId", "sensitivity");
        assertThat((String) event.get("NEW_VALUES_JSON"))
                .contains("\"ownerId\":" + colleague)
                .doesNotContain("teamId", "sensitivity");
        assertThat(jdbc.queryForObject("SELECT OWNER_ID FROM CASE_RECORD WHERE ID = ?", Long.class, id()))
                .isEqualTo(colleague);
    }

    @Test
    void theNewOwnerMustHoldAnalystOrManagerInTheTeam() {
        assertThatThrownBy(() ->
                        assignments.reassign(actor(manager, TeamRole.MANAGER), number.toString(), 0, team, reader))
                .satisfies(kind(Kind.INVALID));
        long outsider = seed.user();
        assertThatThrownBy(() ->
                        assignments.reassign(actor(manager, TeamRole.MANAGER), number.toString(), 0, team, outsider))
                .satisfies(kind(Kind.INVALID));
    }

    @Test
    void anUnchangedReassignmentWritesNothing() {
        assignments.reassign(actor(analyst, TeamRole.ANALYST), number.toString(), 0, team, analyst);
        entityManager.flush();

        assertThat(jdbc.queryForObject("SELECT VERSION FROM CASE_RECORD WHERE ID = ?", Integer.class, id()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM AUDIT_EVENT WHERE CASE_ID = ? AND ACTION = 'CASE_REASSIGNED'",
                        Long.class,
                        id()))
                .isZero();
    }

    @Test
    void anAnalystOwnerWhoRestrictsKeepsReadingWhileAnotherAnalystNoLongerFindsTheCase() {
        assertThat(cases.view(actor(colleague, TeamRole.ANALYST), number.toString())
                        .title())
                .isEqualTo("Synthetic title");

        assignments.changeSensitivity(actor(analyst, TeamRole.ANALYST), number.toString(), 0, Sensitivity.RESTRICTED);
        entityManager.flush();

        assertThat(cases.view(actor(analyst, TeamRole.ANALYST), number.toString())
                        .sensitivity())
                .isEqualTo(Sensitivity.RESTRICTED);
        assertThat(cases.view(actor(manager, TeamRole.MANAGER), number.toString())
                        .unrestrictable())
                .isTrue();
        assertThatThrownBy(() -> cases.view(actor(colleague, TeamRole.ANALYST), number.toString()))
                .satisfies(kind(Kind.NOT_FOUND));
        assertThatThrownBy(() -> cases.view(actor(reader, TeamRole.READER), number.toString()))
                .satisfies(kind(Kind.NOT_FOUND));
        Map<String, Object> restricted = event("CASE_RESTRICTED");
        assertThat((String) restricted.get("OLD_VALUES_JSON")).contains("\"sensitivity\":\"STANDARD\"");
        assertThat((String) restricted.get("NEW_VALUES_JSON"))
                .contains("\"sensitivity\":\"RESTRICTED\"")
                .doesNotContain("ownerId", "teamId");

        assertThatThrownBy(() -> assignments.changeSensitivity(
                        actor(analyst, TeamRole.ANALYST), number.toString(), 1, Sensitivity.STANDARD))
                .satisfies(kind(Kind.FORBIDDEN));
        assignments.changeSensitivity(actor(manager, TeamRole.MANAGER), number.toString(), 1, Sensitivity.STANDARD);
        entityManager.flush();

        assertThat(cases.view(actor(colleague, TeamRole.ANALYST), number.toString())
                        .sensitivity())
                .isEqualTo(Sensitivity.STANDARD);
        assertThat((String) event("CASE_UNRESTRICTED").get("NEW_VALUES_JSON")).contains("\"sensitivity\":\"STANDARD\"");
    }

    @Test
    void anAnalystOwnerWhoReassignsARestrictedCaseAwayNoLongerReadsIt() {
        assignments.changeSensitivity(actor(analyst, TeamRole.ANALYST), number.toString(), 0, Sensitivity.RESTRICTED);
        entityManager.flush();

        assertThat(assignments.reassign(actor(analyst, TeamRole.ANALYST), number.toString(), 1, team, colleague))
                .isFalse();
        assertThat(cases.view(actor(colleague, TeamRole.ANALYST), number.toString())
                        .sensitivity())
                .isEqualTo(Sensitivity.RESTRICTED);
    }

    @Test
    void reassignIsBlockedWhileAClosureRequestIsPending() {
        CaseNumber pending = seed.caseRow(team, analyst, Sensitivity.STANDARD, CaseStatus.PENDING_CLOSURE);
        for (Actor actor : new Actor[] {actor(analyst, TeamRole.ANALYST), actor(manager, TeamRole.MANAGER)}) {
            assertThatThrownBy(() -> assignments.reassignment(actor, pending.toString()))
                    .satisfies(kind(Kind.FORBIDDEN));
            assertThatThrownBy(() -> assignments.reassign(actor, pending.toString(), 0, team, manager))
                    .satisfies(kind(Kind.FORBIDDEN));
            assertThat(cases.view(actor, pending.toString()).reassignable()).isFalse();
        }
        assertThat(cases.view(actor(manager, TeamRole.MANAGER), number.toString())
                        .reassignable())
                .isTrue();
    }

    @Test
    void staleVersionsAreConflictsBeforeAndAtFlush() {
        assertThatThrownBy(() -> assignments.changeSensitivity(
                        actor(analyst, TeamRole.ANALYST), number.toString(), 7, Sensitivity.RESTRICTED))
                .satisfies(kind(Kind.CONFLICT));

        CaseRecord loaded = entityManager.find(CaseRecord.class, id());
        int version = loaded.getVersion();
        // Another transaction committed between this one's read and its write: the row's version has moved on.
        jdbc.update("UPDATE CASE_RECORD SET VERSION = VERSION + 1 WHERE ID = ?", id());
        assertThatThrownBy(() -> assignments.reassign(
                        actor(analyst, TeamRole.ANALYST), number.toString(), version, team, colleague))
                .satisfies(kind(Kind.CONFLICT));
    }

    @Test
    void unreadableCasesAreNotFoundBeforeAnythingElse() {
        long stranger = seed.user();
        long otherTeam = seed.team();
        seed.member(stranger, otherTeam, TeamRole.MANAGER);
        Actor other = new Actor(
                new UserPermissions(stranger, false, Set.of(), Map.of(otherTeam, TeamRole.MANAGER)), "Stranger");

        assertThatThrownBy(() -> assignments.reassignment(other, number.toString()))
                .satisfies(kind(Kind.NOT_FOUND));
        assertThatThrownBy(() -> assignments.reassign(other, number.toString(), 99, otherTeam, -1))
                .satisfies(kind(Kind.NOT_FOUND));
        assertThatThrownBy(() -> assignments.changeSensitivity(other, number.toString(), 99, Sensitivity.RESTRICTED))
                .satisfies(kind(Kind.NOT_FOUND));
        assertThatThrownBy(() -> assignments.sensitivityChange(other, "not-a-number", Sensitivity.STANDARD))
                .satisfies(kind(Kind.NOT_FOUND));
    }

    private long id() {
        return seed.caseId(number);
    }

    private Map<String, Object> event(String action) {
        return jdbc.queryForMap(
                "SELECT ACTOR_ID, OLD_VALUES_JSON, NEW_VALUES_JSON FROM AUDIT_EVENT WHERE CASE_ID = ? AND ACTION = ?",
                id(),
                action);
    }

    private Actor actor(long userId, TeamRole role) {
        return new Actor(new UserPermissions(userId, false, Set.of(), Map.of(team, role)), "Synthetic Actor");
    }

    private CaseFields fields() {
        return new CaseFields(
                seed.caseType(),
                "Synthetic title",
                CasePriority.NORMAL,
                LocalDate.now(ZoneOffset.UTC).minusDays(3),
                team,
                analyst,
                "Synthetic subject",
                "Synthetic description",
                Sensitivity.STANDARD);
    }

    private static Consumer<Throwable> kind(Kind kind) {
        return thrown -> assertThat(thrown)
                .isInstanceOf(CaseException.class)
                .extracting(e -> ((DomainException) e).kind())
                .isEqualTo(kind);
    }
}
