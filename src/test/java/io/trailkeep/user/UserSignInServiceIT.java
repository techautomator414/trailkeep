// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import jakarta.persistence.EntityManager;
import java.time.Instant;
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
        assertThat(service.roleConflicts()).noneMatch(conflict -> conflict.userId() == account.id());
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
        assertThat(service.roleConflicts()).anyMatch(conflict -> conflict.userId() == account.id());

        memberships.delete(memberships.findByIdUserId(account.id()).getFirst());
        flushAndClear();
        UserAccount resolved = signedIn(service.signIn(identity(subject, "Lead"), Set.of(GlobalRole.SYSTEM_ADMIN)));

        assertThat(resolved.globalRoles()).containsExactly(GlobalRole.SYSTEM_ADMIN);
        assertThat(service.roleConflicts()).noneMatch(conflict -> conflict.userId() == account.id());
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

        SignInOutcome outcome = service.signIn(identity(subject, "After"), Set.of(GlobalRole.COMPLIANCE_ADMIN));
        flushAndClear();

        assertThat(outcome).isEqualTo(new SignInOutcome.Disabled(account.id()));
        AppUser after = users.findById(account.id()).orElseThrow();
        assertThat(after.getDisplayName()).isEqualTo("Before");
        assertThat(after.getLastLoginAt()).isEqualTo(lastLoginBefore);
        assertThat(globalRoles.findByIdUserId(account.id())).isEmpty();
    }

    private static UserAccount signedIn(SignInOutcome outcome) {
        assertThat(outcome).isInstanceOf(SignInOutcome.SignedIn.class);
        return ((SignInOutcome.SignedIn) outcome).account();
    }

    private static ExternalIdentity identity(String subject, String name) {
        return new ExternalIdentity(subject, name, "user@example.test");
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
