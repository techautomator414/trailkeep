// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecord;
import io.trailkeep.audit.AuditTrailService;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** Provisioning, global-role sync, the System-Admin conflict rule and disabled refusal, on PostgreSQL. */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class UserSignInServiceIT {

    private static final String EMAIL = "user@example.test";

    @Autowired
    private UserSignInService service;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private TeamRepository teams;

    @Autowired
    private AppUserTeamRepository memberships;

    @Autowired
    private AppUserGlobalRoleRepository globalRoles;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private AuditTrailService auditTrail;

    @Autowired
    private UserDirectoryService directory;

    @Test
    void firstLoginCreatesTheUserAndLaterLoginsRefreshTheProfile() {
        String subject = subject();

        UserAccount first = signedIn(service.signIn(identity(subject, "Old Name"), Set.of()));
        UserAccount second = signedIn(service.signIn(identity(subject, "New Name"), Set.of()));
        flushAndClear();

        assertThat(second.id()).isEqualTo(first.id());
        AppUser stored = users.findByExternalId(subject).orElseThrow();
        assertThat(stored.getDisplayName()).isEqualTo("New Name");
        assertThat(stored.getLastLoginAt()).isNotNull();
        assertThat(second.teamRoles()).isEmpty();
        assertThat(actions(first.id())).containsExactly("USER_PROVISIONED", "LOGIN", "LOGIN");
        assertThat(audit(first.id())).allSatisfy(event -> {
            assertThat(event.actorId()).isEqualTo(first.id());
            assertThat(event.entityType()).isEqualTo("USER");
            assertThat(event.origin()).isEqualTo(AuditOrigin.UI);
            assertThat(event.caseId()).isNull();
            assertThat(event.actorName()).isIn("Old Name", "New Name").doesNotContain("@");
            assertThat(String.valueOf(event.changes().oldValuesJson())
                            + event.changes().newValuesJson())
                    .doesNotContain(EMAIL);
        });
    }

    @Test
    void globalRolesFollowTheClaimsAtEachLogin() {
        String subject = subject();

        signedIn(service.signIn(identity(subject, "Admin"), Set.of(GlobalRole.COMPLIANCE_ADMIN)));
        flushAndClear();
        UserAccount account = signedIn(service.signIn(identity(subject, "Admin"), Set.of(GlobalRole.SYSTEM_ADMIN)));
        flushAndClear();

        assertThat(account.globalRoles()).containsExactly(GlobalRole.SYSTEM_ADMIN);
        assertThat(globalRoles.findByIdUserId(account.id()))
                .extracting(row -> row.getId().role())
                .containsExactly(GlobalRole.SYSTEM_ADMIN);
        assertThat(directory.roleConflicts()).noneMatch(conflict -> conflict.userId() == account.id());
        List<AuditRecord> roleChanges = audit(account.id()).stream()
                .filter(event -> event.action().equals("GLOBAL_ROLES_CHANGED"))
                .toList();
        assertThat(roleChanges).hasSize(2);
        assertThat(roleChanges.getFirst().changes().oldValuesJson()).isEqualTo("{\"globalRoles\":\"\"}");
        assertThat(roleChanges.getFirst().changes().newValuesJson())
                .isEqualTo("{\"globalRoles\":\"COMPLIANCE_ADMIN\"}");
        assertThat(roleChanges.getLast().changes().oldValuesJson()).isEqualTo("{\"globalRoles\":\"COMPLIANCE_ADMIN\"}");
        assertThat(roleChanges.getLast().changes().newValuesJson()).isEqualTo("{\"globalRoles\":\"SYSTEM_ADMIN\"}");
    }

    @Test
    void unchangedGlobalRolesAreNotReaudited() {
        String subject = subject();

        UserAccount account = signedIn(service.signIn(identity(subject, "Same"), Set.of(GlobalRole.COMPLIANCE_ADMIN)));
        signedIn(service.signIn(identity(subject, "Same"), Set.of(GlobalRole.COMPLIANCE_ADMIN)));
        flushAndClear();

        assertThat(actions(account.id())).containsExactly("USER_PROVISIONED", "LOGIN", "GLOBAL_ROLES_CHANGED", "LOGIN");
    }

    @Test
    void systemAdminIsWithheldFromAUserWhoHoldsTeamRolesAndTheConflictIsListed() {
        String subject = subject();
        UserAccount account = signedIn(service.signIn(identity(subject, "Lead"), Set.of()));
        Team team = teams.save(new Team("Team " + UUID.randomUUID()));
        flushAndClear();
        memberships.save(new AppUserTeam(new AppUserTeamId(account.id(), id(team)), TeamRole.MANAGER));
        flushAndClear();

        UserAccount again = signedIn(service.signIn(
                identity(subject, "Lead"), Set.of(GlobalRole.SYSTEM_ADMIN, GlobalRole.COMPLIANCE_ADMIN)));
        flushAndClear();

        assertThat(again.globalRoles()).containsExactly(GlobalRole.COMPLIANCE_ADMIN);
        assertThat(again.teamRoles()).isEqualTo(Map.of(team.getName(), TeamRole.MANAGER));
        assertThat(globalRoles.findByIdUserId(account.id()))
                .extracting(row -> row.getId().role())
                .containsExactly(GlobalRole.COMPLIANCE_ADMIN);
        assertThat(directory.roleConflicts()).anyMatch(conflict -> conflict.userId() == account.id());
        assertThat(actions(account.id())).containsSubsequence("LOGIN", "ROLE_CONFLICT", "GLOBAL_ROLES_CHANGED");

        memberships.delete(memberships.findByIdUserId(account.id()).getFirst());
        flushAndClear();
        UserAccount resolved = signedIn(service.signIn(identity(subject, "Lead"), Set.of(GlobalRole.SYSTEM_ADMIN)));

        assertThat(resolved.globalRoles()).containsExactly(GlobalRole.SYSTEM_ADMIN);
        assertThat(directory.roleConflicts()).noneMatch(conflict -> conflict.userId() == account.id());
    }

    @Test
    void disabledUserIsRefusedAndNothingIsUpdated() {
        String subject = subject();
        UserAccount account = signedIn(service.signIn(identity(subject, "Before"), Set.of()));
        users.findById(account.id()).orElseThrow().disable();
        flushAndClear();
        final @Nullable Instant lastLoginBefore =
                users.findById(account.id()).orElseThrow().getLastLoginAt();
        flushAndClear();

        final int eventsBefore = audit(account.id()).size();

        SignInOutcome outcome = service.signIn(identity(subject, "After"), Set.of(GlobalRole.COMPLIANCE_ADMIN));
        flushAndClear();

        assertThat(outcome).isEqualTo(new SignInOutcome.Disabled(account.id()));
        AppUser after = users.findById(account.id()).orElseThrow();
        assertThat(after.getDisplayName()).isEqualTo("Before");
        assertThat(after.getLastLoginAt()).isEqualTo(lastLoginBefore);
        assertThat(globalRoles.findByIdUserId(account.id())).isEmpty();
        List<String> actions = actions(account.id());
        assertThat(actions).hasSize(eventsBefore + 1).endsWith("LOGIN_REFUSED");
    }

    /** This user's audit events, oldest first. */
    private List<AuditRecord> audit(long userId) {
        List<AuditRecord> events = new ArrayList<>(auditTrail.recent(CaseScope.system(), 500).stream()
                .filter(event -> Long.valueOf(userId).equals(event.entityId()))
                .toList());
        Collections.reverse(events);
        return events;
    }

    private List<String> actions(long userId) {
        return audit(userId).stream().map(AuditRecord::action).toList();
    }

    private static UserAccount signedIn(SignInOutcome outcome) {
        assertThat(outcome).isInstanceOf(SignInOutcome.SignedIn.class);
        return ((SignInOutcome.SignedIn) outcome).account();
    }

    private static ExternalIdentity identity(String subject, String name) {
        return new ExternalIdentity(subject, name, EMAIL);
    }

    private static String subject() {
        return "subject-" + UUID.randomUUID();
    }

    private static Long id(Team team) {
        return java.util.Objects.requireNonNull(team.getId());
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
