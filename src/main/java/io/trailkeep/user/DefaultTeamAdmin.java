// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import io.trailkeep.audit.AuditChanges;
import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.persistence.UniqueViolations;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultTeamAdmin implements TeamAdminService {

    static final int MAX_NAME = 120;

    /** Audit snapshot of a team. */
    record TeamSnapshot(String name, boolean active) {}

    /** Audit snapshot of a membership. */
    record MembershipSnapshot(long userId, TeamRole role) {}

    private final TeamRepository teams;
    private final AppUserRepository users;
    private final AppUserTeamRepository memberships;
    private final AppUserGlobalRoleRepository globalRoles;
    private final UserDirectoryService directory;
    private final AuditRecorder audit;

    DefaultTeamAdmin(
            TeamRepository teams,
            AppUserRepository users,
            AppUserTeamRepository memberships,
            AppUserGlobalRoleRepository globalRoles,
            UserDirectoryService directory,
            AuditRecorder audit) {
        this.teams = teams;
        this.users = users;
        this.memberships = memberships;
        this.globalRoles = globalRoles;
        this.directory = directory;
        this.audit = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TeamSummary> teams(Actor actor) {
        requireSystemAdmin(actor);
        return teams.findAllByOrderByNameAsc().stream()
                .map(DefaultTeamAdmin::summary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserChoice> assignableUsers(Actor actor) {
        requireSystemAdmin(actor);
        Set<Long> systemAdmins = globalRoles.findByIdRole(GlobalRole.SYSTEM_ADMIN).stream()
                .map(role -> role.getId().userId())
                .collect(Collectors.toSet());
        return users.findByDisabledFalseOrderByDisplayNameAsc().stream()
                .filter(user -> !systemAdmins.contains(user.getId()))
                .map(user -> new UserChoice(id(user.getId()), user.getDisplayName()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TeamSummary team(Actor actor, long teamId) {
        requireSystemAdmin(actor);
        return summary(findTeam(teamId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Member> members(Actor actor, long teamId) {
        requireSystemAdmin(actor);
        Map<Long, TeamRole> roles = memberships.findByIdTeamId(teamId).stream()
                .collect(Collectors.toMap(m -> m.getId().userId(), AppUserTeam::getRole));
        return users.findByIdIn(roles.keySet()).stream()
                .map(user -> new Member(
                        id(user.getId()),
                        user.getDisplayName(),
                        Objects.requireNonNull(roles.get(user.getId())),
                        user.isDisabled()))
                .sorted(Comparator.comparing(Member::displayName))
                .toList();
    }

    @Override
    public long createTeam(Actor actor, String name) {
        requireSystemAdmin(actor);
        String clean = validName(name);
        if (teams.findByName(clean).isPresent()) {
            throw new TeamAdminException(Kind.CONFLICT, "a team with this name exists");
        }
        Team team = teams.save(new Team(clean));
        flushOrNameTaken();
        long id = id(team.getId());
        record(actor, id, "TEAM", "TEAM_CREATED", audit.diff(null, snapshot(team)));
        return id;
    }

    @Override
    public void updateTeam(Actor actor, long teamId, String name, boolean active) {
        requireSystemAdmin(actor);
        Team team = findTeam(teamId);
        String clean = validName(name);
        if (teams.findByName(clean)
                .filter(other -> !Objects.equals(other.getId(), team.getId()))
                .isPresent()) {
            throw new TeamAdminException(Kind.CONFLICT, "a team with this name exists");
        }
        TeamSnapshot before = snapshot(team);
        team.rename(clean);
        team.setActive(active);
        AuditChanges changes = audit.diff(before, snapshot(team));
        flushOrNameTaken();
        if (!changes.equals(AuditChanges.none())) {
            record(actor, teamId, "TEAM", "TEAM_UPDATED", changes);
        }
    }

    @Override
    public void assignRole(Actor actor, long teamId, long userId, TeamRole role) {
        requireSystemAdmin(actor);
        Team team = findTeam(teamId);
        // The import principals (ADR-0015) never hold a role.
        if (users.findById(userId).filter(user -> !user.isPrincipal()).isEmpty()) {
            throw new TeamAdminException(Kind.NOT_FOUND, "no such user");
        }
        UserPermissions target = directory.permissionsOf(userId);
        if (target.disabled() || !team.isActive()) {
            throw new TeamAdminException(Kind.INVALID, "the user is disabled or the team is inactive");
        }
        if (target.has(GlobalRole.SYSTEM_ADMIN)) {
            throw new TeamAdminException(Kind.INVALID, "a System Admin cannot hold a team role");
        }
        AppUserTeamId id = new AppUserTeamId(userId, teamId);
        AppUserTeam existing = memberships.findById(id).orElse(null);
        if (existing != null && existing.getRole() == role) {
            return;
        }
        MembershipSnapshot before = existing == null ? null : new MembershipSnapshot(userId, existing.getRole());
        if (existing == null) {
            memberships.save(new AppUserTeam(id, role));
        } else {
            existing.changeRole(role);
        }
        String action = existing == null ? "ROLE_GRANTED" : "ROLE_CHANGED";
        record(actor, teamId, "TEAM_MEMBERSHIP", action, audit.diff(before, new MembershipSnapshot(userId, role)));
    }

    @Override
    public void revokeRole(Actor actor, long teamId, long userId) {
        requireSystemAdmin(actor);
        findTeam(teamId);
        AppUserTeam existing = memberships
                .findById(new AppUserTeamId(userId, teamId))
                .orElseThrow(() -> new TeamAdminException(Kind.NOT_FOUND, "the user is not a member"));
        memberships.delete(existing);
        MembershipSnapshot before = new MembershipSnapshot(userId, existing.getRole());
        record(actor, teamId, "TEAM_MEMBERSHIP", "ROLE_REVOKED", audit.diff(before, null));
    }

    private static void requireSystemAdmin(Actor actor) {
        if (!actor.permissions().has(GlobalRole.SYSTEM_ADMIN)) {
            throw new TeamAdminException(Kind.FORBIDDEN, "System Admin only");
        }
    }

    private Team findTeam(long teamId) {
        return teams.findById(teamId).orElseThrow(() -> new TeamAdminException(Kind.NOT_FOUND, "no such team"));
    }

    private static String validName(String name) {
        String clean = name.strip();
        if (clean.isEmpty() || clean.length() > MAX_NAME) {
            throw new TeamAdminException(Kind.INVALID, "a team name has 1 to " + MAX_NAME + " characters");
        }
        return clean;
    }

    private static TeamSummary summary(Team team) {
        return new TeamSummary(id(team.getId()), team.getName(), team.isActive());
    }

    /**
     * The name check is not atomic: a concurrent request can take the name before this one writes. The unique
     * constraint decides, so the write is flushed here and its violation answered like the check, never left to fail
     * at commit as a 500.
     */
    private void flushOrNameTaken() {
        try {
            teams.flush();
        } catch (DataIntegrityViolationException failure) {
            if (UniqueViolations.isUnique(failure)) {
                throw new TeamAdminException(Kind.CONFLICT, "a team with this name exists");
            }
            throw failure;
        }
    }

    private static long id(@Nullable Long id) {
        if (id == null) {
            throw new IllegalStateException("a persisted entity has an id");
        }
        return id;
    }

    private static TeamSnapshot snapshot(Team team) {
        return new TeamSnapshot(team.getName(), team.isActive());
    }

    private void record(Actor actor, long teamId, String entityType, String action, AuditChanges changes) {
        audit.record(new AuditEntry(
                actor.permissions().userId(),
                actor.displayName(),
                AuditOrigin.UI,
                null,
                entityType,
                teamId,
                action,
                changes));
    }
}
