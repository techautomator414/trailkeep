// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A user's authority as the database holds it now: global roles plus one role per team (BUILD_PLAN §3.2). Built
 * per request by {@link UserDirectoryService#permissionsOf}, never stored in the session, so a role change or a
 * disable takes effect on the next request. A disabled or unknown user holds nothing.
 *
 * @param userId the user's id
 * @param disabled whether the user is disabled (or unknown)
 * @param globalRoles the global roles
 * @param teamRoles the role in each team, by team id
 */
public record UserPermissions(
        long userId, boolean disabled, Set<GlobalRole> globalRoles, Map<Long, TeamRole> teamRoles) {

    /** Copies the collections; a disabled user's roles are dropped. */
    public UserPermissions {
        globalRoles = disabled ? Set.of() : Set.copyOf(globalRoles);
        teamRoles = disabled ? Map.of() : Map.copyOf(teamRoles);
    }

    /**
     * The permissions of a user who holds nothing.
     *
     * @param userId the user's id
     * @return disabled, empty permissions
     */
    public static UserPermissions none(long userId) {
        return new UserPermissions(userId, true, Set.of(), Map.of());
    }

    /**
     * Whether the user holds a global role.
     *
     * @param role the role
     * @return {@code true} if held
     */
    public boolean has(GlobalRole role) {
        return globalRoles.contains(role);
    }

    /**
     * The user's role in one team.
     *
     * @param teamId the team id
     * @return the role, or {@code null} if the user is not a member
     */
    public @Nullable TeamRole roleIn(long teamId) {
        return teamRoles.get(teamId);
    }
}
