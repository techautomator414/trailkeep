// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    public List<Long> activeMembersOf(long teamId) {
        return enabled(memberships.findByIdTeamId(teamId).stream()
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
    public Optional<String> mailAddress(long userId) {
        return users.findById(userId)
                .filter(user -> !user.isDisabled())
                .map(AppUser::getEmail)
                .filter(email -> !email.isBlank());
    }

    @Override
    public Optional<TeamAdminService.TeamSummary> team(long teamId) {
        return teams.findById(teamId)
                .map(team -> new TeamAdminService.TeamSummary(teamId, team.getName(), team.isActive()));
    }

    @Override
    public List<TeamAdminService.TeamSummary> allTeams() {
        return teams.findAllByOrderByNameAsc().stream()
                .map(team -> new TeamAdminService.TeamSummary(
                        Objects.requireNonNull(team.getId()), team.getName(), team.isActive()))
                .toList();
    }

    @Override
    public Map<Long, String> displayNames(Collection<Long> userIds) {
        return userIds.isEmpty()
                ? Map.of()
                : users.findByIdIn(userIds).stream()
                        .collect(Collectors.toUnmodifiableMap(AppUser::getId, AppUser::getDisplayName));
    }

    @Override
    public Map<Long, TeamAdminService.TeamSummary> teams(Collection<Long> teamIds) {
        return teamIds.isEmpty()
                ? Map.of()
                : teams.findByIdIn(teamIds).stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Team::getId,
                                team -> new TeamAdminService.TeamSummary(
                                        Objects.requireNonNull(team.getId()), team.getName(), team.isActive())));
    }

    @Override
    public List<TeamAdminService.UserChoice> eligibleOwners(long teamId) {
        Set<Long> systemAdmins = globalRoles.findByIdRole(GlobalRole.SYSTEM_ADMIN).stream()
                .map(role -> role.getId().userId())
                .collect(Collectors.toUnmodifiableSet());
        List<Long> owners = memberships.findByIdTeamId(teamId).stream()
                .filter(m -> m.getRole() == TeamRole.ANALYST || m.getRole() == TeamRole.MANAGER)
                .map(m -> m.getId().userId())
                .filter(id -> !systemAdmins.contains(id))
                .toList();
        return users.findByIdIn(owners).stream()
                .filter(user -> !user.isDisabled())
                .sorted(Comparator.comparing(AppUser::getDisplayName))
                .map(user ->
                        new TeamAdminService.UserChoice(Objects.requireNonNull(user.getId()), user.getDisplayName()))
                .toList();
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
