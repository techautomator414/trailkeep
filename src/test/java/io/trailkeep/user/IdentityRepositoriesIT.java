// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** The identity entities round-trip through V1 on PostgreSQL, and the schema's keys hold. */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class IdentityRepositoriesIT {

    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05Z");

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
    void userIsStoredWithPooledSequenceIdAndFoundByExternalId() {
        String externalId = "subject-" + UUID.randomUUID();
        AppUser saved = users.save(new AppUser(externalId, "Sample Analyst", "analyst@example.test", CREATED));
        saved.recordLogin(CREATED.plusSeconds(60));
        flushAndClear();

        AppUser found = users.findByExternalId(externalId).orElseThrow();

        assertThat(found.getId()).isNotNull().isEqualTo(saved.getId());
        assertThat(found.getDisplayName()).isEqualTo("Sample Analyst");
        assertThat(found.getEmail()).isEqualTo("analyst@example.test");
        assertThat(found.isDisabled()).isFalse();
        assertThat(found.getCreatedAt()).isEqualTo(CREATED);
        assertThat(found.getLastLoginAt()).isEqualTo(CREATED.plusSeconds(60));
        assertThat(users.findById(id(found))).contains(found);
    }

    @Test
    void theExternalIdIsUnique() {
        String externalId = "subject-" + UUID.randomUUID();
        users.save(new AppUser(externalId, "First", "first@example.test", CREATED));
        flushAndClear();

        users.save(new AppUser(externalId, "Second", "second@example.test", CREATED));
        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(entityManager::flush)
                .satisfies(e -> assertThat(e.getConstraintName()).containsIgnoringCase("UK_APP_USER_EXT"));
    }

    @Test
    void membershipsAndGlobalRolesAreFoundByUserAndByTeam() {
        AppUser user = users.save(new AppUser("subject-" + UUID.randomUUID(), "Lead", "lead@example.test", CREATED));
        Team team = teams.save(new Team("Team " + UUID.randomUUID()));
        flushAndClear();
        AppUserTeamId membershipId = new AppUserTeamId(id(user), id(team));
        memberships.save(new AppUserTeam(membershipId, TeamRole.ANALYST));
        globalRoles.save(new AppUserGlobalRole(new AppUserGlobalRoleId(id(user), GlobalRole.COMPLIANCE_ADMIN)));
        flushAndClear();

        AppUserTeam membership = memberships.findByIdUserId(id(user)).getFirst();
        membership.changeRole(TeamRole.MANAGER);
        flushAndClear();

        assertThat(memberships.findByIdTeamId(id(team)))
                .singleElement()
                .satisfies(found -> assertThat(found.getRole()).isEqualTo(TeamRole.MANAGER));
        assertThat(teams.findByName(team.getName())).contains(team);
        assertThat(globalRoles.findByIdUserId(id(user)))
                .extracting(role -> role.getId().role())
                .containsExactly(GlobalRole.COMPLIANCE_ADMIN);

        memberships.delete(memberships.findByIdUserId(id(user)).getFirst());
        globalRoles.delete(globalRoles.findByIdUserId(id(user)).getFirst());
        flushAndClear();

        assertThat(memberships.findByIdUserId(id(user))).isEmpty();
        assertThat(globalRoles.findByIdUserId(id(user))).isEmpty();
    }

    @Test
    void membershipNeedsAnExistingUserAndTeam() {
        memberships.save(new AppUserTeam(new AppUserTeamId(-1L, -1L), TeamRole.READER));

        assertThatExceptionOfType(ConstraintViolationException.class).isThrownBy(entityManager::flush);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static Long id(AppUser user) {
        return Objects.requireNonNull(user.getId());
    }

    private static Long id(Team team) {
        return Objects.requireNonNull(team.getId());
    }
}
