// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultUserDirectory implements UserDirectoryService {

    private final AppUserRepository users;
    private final AppUserTeamRepository memberships;
    private final AppUserGlobalRoleRepository globalRoles;
    private final TeamRepository teams;

    DefaultUserDirectory(
            AppUserRepository users,
            AppUserTeamRepository memberships,
            AppUserGlobalRoleRepository globalRoles,
            TeamRepository teams) {
        this.users = users;
        this.teams = teams;
        this.memberships = memberships;
        this.globalRoles = globalRoles;
    }

    @Override
    public UserPermissions permissionsOf(long userId) {
        return users.findById(userId)
                .map(user -> new UserPermissions(
                        userId,
                        user.isDisabled(),
                        globalRoles.findByIdUserId(userId).stream()
                                .map(role -> role.getId().role())
                                .collect(Collectors.toSet()),
                        memberships.findByIdUserId(userId).stream()
                                .collect(Collectors.toMap(m -> m.getId().teamId(), AppUserTeam::getRole))))
                .orElseGet(() -> UserPermissions.none(userId));
    }

    @Override
    public List<Long> activeManagersOf(long teamId) {
        return enabled(memberships.findByIdTeamId(teamId).stream()
                .filter(m -> m.getRole() == TeamRole.MANAGER)
                .map(m -> m.getId().userId())
                .toList());
    }

    @Override
    public List<Long> activeComplianceAdmins() {
        return enabled(globalRoles.findByIdRole(GlobalRole.COMPLIANCE_ADMIN).stream()
                .map(role -> role.getId().userId())
                .toList());
    }

    @Override
    public Optional<String> displayName(long userId) {
        return users.findById(userId).map(AppUser::getDisplayName);
    }

    @Override
    public Optional<TeamAdminService.TeamSummary> team(long teamId) {
        return teams.findById(teamId)
                .map(team -> new TeamAdminService.TeamSummary(teamId, team.getName(), team.isActive()));
    }

    private List<Long> enabled(Collection<Long> ids) {
        Map<Long, Boolean> disabled =
                users.findByIdIn(ids).stream().collect(Collectors.toMap(AppUser::getId, AppUser::isDisabled));
        return ids.stream()
                .filter(id -> Boolean.FALSE.equals(disabled.get(id)))
                .sorted()
                .toList();
    }
}
