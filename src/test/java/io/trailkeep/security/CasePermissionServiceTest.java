// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserDirectoryService;
import io.trailkeep.user.UserPermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class CasePermissionServiceTest {

    private static final long TEAM = 10L;
    private static final long OTHER_TEAM = 20L;

    private List<Long> managers = List.of();
    private List<Long> complianceAdmins = List.of();

    private final CasePermissionService service = new DefaultCasePermissionService(new UserDirectoryService() {
        @Override
        public UserPermissions permissionsOf(long userId) {
            return UserPermissions.none(userId);
        }

        @Override
        public List<Long> activeManagersOf(long teamId) {
            return managers;
        }

        @Override
        public List<Long> activeComplianceAdmins() {
            return complianceAdmins;
        }
    });

    @Test
    void globalRolesWinOverTeamRolesAndTeamRolesApplyOnlyToTheirTeam() {
        assertThat(DefaultCasePermissionService.roleOf(
                        user(1, Set.of(GlobalRole.SYSTEM_ADMIN), TeamRole.MANAGER), TEAM))
                .isEqualTo(MatrixRole.SYSTEM_ADMIN);
        assertThat(DefaultCasePermissionService.roleOf(user(1, Set.of(GlobalRole.COMPLIANCE_ADMIN), null), OTHER_TEAM))
                .isEqualTo(MatrixRole.COMPLIANCE_ADMIN);
        assertThat(DefaultCasePermissionService.roleOf(user(1, Set.of(), TeamRole.ANALYST), OTHER_TEAM))
                .isEqualTo(MatrixRole.NONE);
    }

    @Test
    void decisionsUseOwnershipFromTheCaseFacts() {
        UserPermissions analyst = user(5, Set.of(), TeamRole.ANALYST);

        assertThat(service.isAllowed(analyst, facts(5L, Sensitivity.RESTRICTED), CaseAction.VIEW))
                .isTrue();
        assertThat(service.isAllowed(analyst, facts(6L, Sensitivity.RESTRICTED), CaseAction.VIEW))
                .isFalse();
        assertThat(service.isAllowed(analyst, facts(null, Sensitivity.STANDARD), CaseAction.REASSIGN))
                .isFalse();
    }

    @Test
    void disabledUsersMayDoNothing() {
        UserPermissions disabled =
                new UserPermissions(5, true, Set.of(GlobalRole.COMPLIANCE_ADMIN), Map.of(TEAM, TeamRole.MANAGER));

        assertThat(service.isAllowed(disabled, facts(5L, Sensitivity.STANDARD), CaseAction.VIEW))
                .isFalse();
        assertThat(service.mayOwn(disabled, TEAM)).isFalse();
    }

    @Test
    void ownersHoldAnalystOrManagerInTheCasesTeam() {
        assertThat(service.mayOwn(user(1, Set.of(), TeamRole.ANALYST), TEAM)).isTrue();
        assertThat(service.mayOwn(user(1, Set.of(), TeamRole.MANAGER), TEAM)).isTrue();
        assertThat(service.mayOwn(user(1, Set.of(), TeamRole.READER), TEAM)).isFalse();
        assertThat(service.mayOwn(user(1, Set.of(), TeamRole.MANAGER), OTHER_TEAM))
                .isFalse();
        assertThat(service.mayOwn(user(1, Set.of(GlobalRole.COMPLIANCE_ADMIN), null), TEAM))
                .isFalse();
        assertThat(service.mayOwn(user(1, Set.of(GlobalRole.SYSTEM_ADMIN), TeamRole.MANAGER), TEAM))
                .isFalse();
    }

    @Test
    void approversAreTheTeamsManagersMinusRequesterAndOwner() {
        managers = List.of(1L, 2L, 3L);
        complianceAdmins = List.of(9L);

        assertThat(service.approverCandidates(facts(2L, Sensitivity.STANDARD), 3L))
                .containsExactly(1L);
    }

    @Test
    void complianceAdminsApproveWhenNoManagerRemains() {
        managers = List.of(2L, 3L);
        complianceAdmins = List.of(2L, 8L, 9L);

        assertThat(service.approverCandidates(facts(2L, Sensitivity.STANDARD), 3L))
                .containsExactly(8L, 9L);
    }

    private static UserPermissions user(long id, Set<GlobalRole> global, @Nullable TeamRole roleInTeam) {
        return new UserPermissions(id, false, global, roleInTeam == null ? Map.of() : Map.of(TEAM, roleInTeam));
    }

    private static CaseFacts facts(@Nullable Long ownerId, Sensitivity sensitivity) {
        return new CaseFacts(TEAM, ownerId, sensitivity, CaseStatus.OPEN);
    }
}
