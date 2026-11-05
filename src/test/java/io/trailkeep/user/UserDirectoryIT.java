// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** {@link UserDirectoryService} rebuilds permissions from the database on every call, on PostgreSQL. */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class UserDirectoryIT {

    @Autowired
    private UserDirectoryService directory;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private TeamRepository teams;

    @Autowired
    private AppUserTeamRepository memberships;

    @Autowired
    private AppUserGlobalRoleRepository globalRoles;

    @Test
    void permissionsReflectTheDatabaseAtTheTimeOfTheCall() {
        long team = team();
        long user = user();
        AppUserTeam membership = memberships.save(new AppUserTeam(new AppUserTeamId(user, team), TeamRole.READER));
        assertThat(directory.permissionsOf(user).roleIn(team)).isEqualTo(TeamRole.READER);

        membership.changeRole(TeamRole.MANAGER);
        globalRoles.save(new AppUserGlobalRole(new AppUserGlobalRoleId(user, GlobalRole.COMPLIANCE_ADMIN)));

        UserPermissions now = directory.permissionsOf(user);
        assertThat(now.roleIn(team)).isEqualTo(TeamRole.MANAGER);
        assertThat(now.has(GlobalRole.COMPLIANCE_ADMIN)).isTrue();
        assertThat(now.disabled()).isFalse();
    }

    @Test
    void disabledAndUnknownUsersHoldNothing() {
        long team = team();
        long user = user();
        memberships.save(new AppUserTeam(new AppUserTeamId(user, team), TeamRole.MANAGER));
        users.findById(user).orElseThrow().disable();

        assertThat(directory.permissionsOf(user)).isEqualTo(UserPermissions.none(user));
        assertThat(directory.permissionsOf(Long.MAX_VALUE)).isEqualTo(UserPermissions.none(Long.MAX_VALUE));
    }

    @Test
    void approverDirectoriesListOnlyEnabledHolders() {
        long team = team();
        long manager = user();
        long leaver = user();
        long reader = user();
        memberships.save(new AppUserTeam(new AppUserTeamId(manager, team), TeamRole.MANAGER));
        memberships.save(new AppUserTeam(new AppUserTeamId(leaver, team), TeamRole.MANAGER));
        memberships.save(new AppUserTeam(new AppUserTeamId(reader, team), TeamRole.READER));
        globalRoles.save(new AppUserGlobalRole(new AppUserGlobalRoleId(leaver, GlobalRole.COMPLIANCE_ADMIN)));
        globalRoles.save(new AppUserGlobalRole(new AppUserGlobalRoleId(reader, GlobalRole.COMPLIANCE_ADMIN)));
        users.findById(leaver).orElseThrow().disable();

        assertThat(directory.activeManagersOf(team)).containsExactly(manager);
        assertThat(directory.activeComplianceAdmins()).contains(reader).doesNotContain(leaver, manager);
    }

    @Test
    void namesAndTeamsAreLookedUpManyAtOnceAndUnknownIdsAreLeftOut() {
        long first = user();
        long second = user();
        long team = team();

        assertThat(directory.displayNames(List.of(first, second, Long.MAX_VALUE)))
                .containsOnlyKeys(first, second)
                .containsValue("Synthetic User");
        assertThat(directory.teams(List.of(team, Long.MAX_VALUE)))
                .containsOnlyKeys(team)
                .hasEntrySatisfying(
                        team, summary -> assertThat(summary.active()).isTrue());
        assertThat(directory.displayNames(List.of())).isEmpty();
        assertThat(directory.teams(List.of())).isEmpty();
    }

    private long user() {
        AppUser user = users.save(new AppUser(
                "ext-" + UUID.randomUUID(),
                "Synthetic User",
                "user@example.invalid",
                Instant.parse("2026-01-01T00:00:00Z")));
        return user.getId() == null ? 0 : user.getId();
    }

    private long team() {
        Team team = teams.save(new Team("Team " + UUID.randomUUID()));
        return team.getId() == null ? 0 : team.getId();
    }
}
