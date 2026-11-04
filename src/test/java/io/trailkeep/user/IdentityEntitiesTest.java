// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IdentityEntitiesTest {

    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");

    @Test
    void newUserIsEnabledAndHasNeverLoggedIn() {
        AppUser user = new AppUser("subject-1", "Sample Reader", "reader@example.test", NOW);

        assertThat(user.getId()).isNull();
        assertThat(user.getExternalId()).isEqualTo("subject-1");
        assertThat(user.isDisabled()).isFalse();
        assertThat(user.getLastLoginAt()).isNull();
        assertThat(user.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void userCanBeDisabledReenabledAndRefreshedFromTheProvider() {
        AppUser user = new AppUser("subject-1", "Old Name", "old@example.test", NOW);

        user.disable();
        assertThat(user.isDisabled()).isTrue();
        user.enable();
        user.updateProfile("New Name", "new@example.test");
        user.recordLogin(NOW.plusSeconds(1));

        assertThat(user.isDisabled()).isFalse();
        assertThat(user.getDisplayName()).isEqualTo("New Name");
        assertThat(user.getEmail()).isEqualTo("new@example.test");
        assertThat(user.getLastLoginAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void userNeverPrintsItsEmailAddress() {
        AppUser user = new AppUser("subject-1", "Sample Reader", "reader@example.test", NOW);

        assertThat(user.toString()).doesNotContain("reader@example.test").doesNotContain("Sample Reader");
    }

    @Test
    void unsavedEntitiesAreEqualOnlyToThemselves() throws ReflectiveOperationException {
        AppUser first = new AppUser("subject-1", "A", "a@example.test", NOW);
        AppUser second = new AppUser("subject-1", "A", "a@example.test", NOW);

        assertThat(first.equals(first)).isTrue();
        assertThat(first).isNotEqualTo(second).hasSameHashCodeAs(second);

        setId(first, 7L);
        setId(second, 7L);
        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEqualTo(new Team("Team A"));
    }

    @Test
    void teamsAreActiveUntilDeactivated() throws ReflectiveOperationException {
        Team team = new Team("Team A");
        assertThat(team.isActive()).isTrue();

        team.deactivate();
        assertThat(team.isActive()).isFalse();
        team.activate();
        team.rename("Team B");

        assertThat(team.isActive()).isTrue();
        assertThat(team.getName()).isEqualTo("Team B");
        assertThat(team.equals(team)).isTrue();
        assertThat(team).isNotEqualTo(new Team("Team B")).hasSameHashCodeAs(new Team("Team C"));
        assertThat(team.getId()).isNull();
        setId(team, 3L);
        Team same = new Team("Other");
        setId(same, 3L);
        assertThat(team).isEqualTo(same).hasToString("Team[id=3, name=Team B, active=true]");
    }

    @Test
    void membershipsAreIdentifiedByUserAndTeam() {
        AppUserTeam membership = new AppUserTeam(new AppUserTeamId(1L, 2L), TeamRole.READER);
        membership.changeRole(TeamRole.ANALYST);

        assertThat(membership.getRole()).isEqualTo(TeamRole.ANALYST);
        assertThat(membership)
                .isEqualTo(new AppUserTeam(new AppUserTeamId(1L, 2L), TeamRole.MANAGER))
                .isNotEqualTo(new AppUserTeam(new AppUserTeamId(1L, 3L), TeamRole.ANALYST))
                .hasSameHashCodeAs(new AppUserTeam(new AppUserTeamId(1L, 2L), TeamRole.READER));
        assertThat(membership.toString()).contains("ANALYST");
        assertThat(membership.getId().teamId()).isEqualTo(2L);
    }

    @Test
    void globalRolesAreIdentifiedByUserAndRole() {
        AppUserGlobalRole role = new AppUserGlobalRole(new AppUserGlobalRoleId(1L, GlobalRole.SYSTEM_ADMIN));

        assertThat(role)
                .isEqualTo(new AppUserGlobalRole(new AppUserGlobalRoleId(1L, GlobalRole.SYSTEM_ADMIN)))
                .isNotEqualTo(new AppUserGlobalRole(new AppUserGlobalRoleId(1L, GlobalRole.COMPLIANCE_ADMIN)))
                .hasSameHashCodeAs(new AppUserGlobalRole(new AppUserGlobalRoleId(1L, GlobalRole.SYSTEM_ADMIN)));
        assertThat(role.toString()).contains("SYSTEM_ADMIN");
        assertThat(role.getId().userId()).isEqualTo(1L);
    }

    @Test
    void jpaConstructorsProduceInertInstances() {
        assertThat(new AppUser().getExternalId()).isEmpty();
        assertThat(new Team().getName()).isEmpty();
        assertThat(new AppUserTeam().getRole()).isEqualTo(TeamRole.READER);
        assertThat(new AppUserGlobalRole().getId().role()).isEqualTo(GlobalRole.COMPLIANCE_ADMIN);
    }

    private static void setId(Object entity, Long id) throws ReflectiveOperationException {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }
}
