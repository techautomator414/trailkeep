// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.audit.AuditRecord;
import io.trailkeep.audit.AuditTrailService;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.TeamAdminException.Kind;
import io.trailkeep.user.TeamAdminService.Actor;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** Team administration, its invariants and its audit trail, and per-request permissions, on PostgreSQL. */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class TeamAdminServiceIT {

    @Autowired
    private TeamAdminService admin;

    @Autowired
    private UserDirectoryService directory;

    @Autowired
    private AuditTrailService trail;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private AppUserGlobalRoleRepository globalRoles;

    private Actor systemAdmin = new Actor(UserPermissions.none(0), "nobody");
    private long analyst;

    @BeforeEach
    void seed() {
        long adminId = user("Synthetic System Admin");
        globalRoles.save(new AppUserGlobalRole(new AppUserGlobalRoleId(adminId, GlobalRole.SYSTEM_ADMIN)));
        systemAdmin = new Actor(directory.permissionsOf(adminId), "Synthetic System Admin");
        analyst = user("Synthetic Analyst");
    }

    @Test
    void roleChangesAreVisibleInTheNextPermissionsBuiltFromTheDatabase() {
        long team = admin.createTeam(systemAdmin, unique("Team"));

        admin.assignRole(systemAdmin, team, analyst, TeamRole.READER);
        assertThat(directory.permissionsOf(analyst).roleIn(team)).isEqualTo(TeamRole.READER);

        admin.assignRole(systemAdmin, team, analyst, TeamRole.ANALYST);
        assertThat(directory.permissionsOf(analyst).roleIn(team)).isEqualTo(TeamRole.ANALYST);

        admin.revokeRole(systemAdmin, team, analyst);
        assertThat(directory.permissionsOf(analyst).roleIn(team)).isNull();
    }

    @Test
    void everyTeamRoleChangeIsAudited() {
        long team = admin.createTeam(systemAdmin, unique("Audited"));
        admin.assignRole(systemAdmin, team, analyst, TeamRole.READER);
        admin.assignRole(systemAdmin, team, analyst, TeamRole.MANAGER);
        admin.revokeRole(systemAdmin, team, analyst);

        assertThat(trail.recent(CaseScope.system(), 10).stream()
                        .filter(event -> Long.valueOf(team).equals(event.entityId())))
                .extracting(AuditRecord::action)
                .containsExactly("ROLE_REVOKED", "ROLE_CHANGED", "ROLE_GRANTED", "TEAM_CREATED");
        AuditRecord change = trail.recent(CaseScope.system(), 10).stream()
                .filter(event -> event.action().equals("ROLE_CHANGED"))
                .findFirst()
                .orElseThrow();
        assertThat(change.changes().oldValuesJson()).isEqualTo("{\"role\":\"READER\"}");
        assertThat(change.changes().newValuesJson()).isEqualTo("{\"role\":\"MANAGER\"}");
        assertThat(change.actorName()).isEqualTo("Synthetic System Admin");
        assertThat(change.caseId()).isNull();
    }

    @Test
    void systemAdminsCannotHoldTeamRoles() {
        long team = admin.createTeam(systemAdmin, unique("SoD"));

        assertThatThrownBy(() -> admin.assignRole(
                        systemAdmin, team, systemAdmin.permissions().userId(), TeamRole.READER))
                .isInstanceOfSatisfying(
                        TeamAdminException.class, e -> assertThat(e.kind()).isEqualTo(Kind.INVALID));
    }

    @Test
    void onlySystemAdminsAdministerTeams() {
        long team = admin.createTeam(systemAdmin, unique("Guarded"));
        globalRoles.save(new AppUserGlobalRole(new AppUserGlobalRoleId(analyst, GlobalRole.COMPLIANCE_ADMIN)));
        Actor complianceAdmin = new Actor(directory.permissionsOf(analyst), "Synthetic Compliance Admin");

        assertThatThrownBy(() -> admin.teams(complianceAdmin)).satisfies(kind(Kind.FORBIDDEN));
        assertThatThrownBy(() -> admin.assignRole(complianceAdmin, team, analyst, TeamRole.MANAGER))
                .satisfies(kind(Kind.FORBIDDEN));
    }

    @Test
    void unknownTeamsAndUsersAreNotFoundAndDuplicateNamesConflict() {
        String name = unique("Taken");
        long team = admin.createTeam(systemAdmin, name);

        assertThatThrownBy(() -> admin.team(systemAdmin, Long.MAX_VALUE)).satisfies(kind(Kind.NOT_FOUND));
        assertThatThrownBy(() -> admin.assignRole(systemAdmin, team, Long.MAX_VALUE, TeamRole.READER))
                .satisfies(kind(Kind.NOT_FOUND));
        assertThatThrownBy(() -> admin.revokeRole(systemAdmin, team, analyst)).satisfies(kind(Kind.NOT_FOUND));
        assertThatThrownBy(() -> admin.createTeam(systemAdmin, " " + name + " "))
                .satisfies(kind(Kind.CONFLICT));
        assertThatThrownBy(() -> admin.createTeam(systemAdmin, "x".repeat(121))).satisfies(kind(Kind.INVALID));
    }

    @Test
    void inactiveTeamsAndDisabledUsersReceiveNoRoles() {
        long team = admin.createTeam(systemAdmin, unique("Dormant"));
        admin.updateTeam(systemAdmin, team, unique("Dormant"), false);

        assertThatThrownBy(() -> admin.assignRole(systemAdmin, team, analyst, TeamRole.READER))
                .satisfies(kind(Kind.INVALID));
        assertThat(admin.team(systemAdmin, team).active()).isFalse();
    }

    @Test
    void approverDirectoryListsOnlyEnabledManagers() {
        long team = admin.createTeam(systemAdmin, unique("Approvers"));
        long manager = user("Synthetic Manager");
        long leaver = user("Synthetic Leaver");
        admin.assignRole(systemAdmin, team, manager, TeamRole.MANAGER);
        admin.assignRole(systemAdmin, team, leaver, TeamRole.MANAGER);
        admin.assignRole(systemAdmin, team, analyst, TeamRole.ANALYST);
        users.findById(leaver).orElseThrow().disable();

        assertThat(directory.activeManagersOf(team)).containsExactly(manager);
        assertThat(directory.permissionsOf(leaver).teamRoles()).isEmpty();
        assertThat(admin.members(systemAdmin, team))
                .extracting(TeamAdminService.Member::displayName)
                .containsExactly("Synthetic Analyst", "Synthetic Leaver", "Synthetic Manager");
    }

    private long user(String name) {
        AppUser user = users.save(new AppUser(
                "ext-" + UUID.randomUUID(), name, "user@example.invalid", Instant.parse("2026-01-01T00:00:00Z")));
        return user.getId() == null ? 0 : user.getId();
    }

    private static String unique(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private static java.util.function.Consumer<Throwable> kind(Kind kind) {
        return e -> assertThat(((TeamAdminException) e).kind()).isEqualTo(kind);
    }
}
