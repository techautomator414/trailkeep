// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.util.List;

/**
 * Teams and team roles, administered by System Admins only (BUILD_PLAN §3.2, §4.6). Every change is audited. Every
 * method throws {@link TeamAdminException}: {@code FORBIDDEN} unless the actor holds System Admin, {@code NOT_FOUND}
 * for an unknown team or user.
 */
public interface TeamAdminService {

    /**
     * Who is acting.
     *
     * @param permissions the actor's permissions, rebuilt for this request
     * @param displayName the actor's display name, kept in the audit trail
     */
    record Actor(UserPermissions permissions, String displayName) {}

    /**
     * A team as the admin screen lists it.
     *
     * @param id the team id
     * @param name the name
     * @param active whether the team accepts new cases and members
     */
    record TeamSummary(long id, String name, boolean active) {}

    /**
     * A team member.
     *
     * @param userId the user's id
     * @param displayName the user's name
     * @param role the role in the team
     * @param disabled whether the user is disabled
     */
    record Member(long userId, String displayName, TeamRole role, boolean disabled) {}

    /**
     * A user who may be given a team role.
     *
     * @param userId the user's id
     * @param displayName the user's name
     */
    record UserChoice(long userId, String displayName) {}

    /**
     * The enabled users who may hold team roles (everyone but System Admins), by name.
     *
     * @param actor the actor
     * @return the users
     */
    List<UserChoice> assignableUsers(Actor actor);

    /**
     * All teams by name.
     *
     * @param actor the actor
     * @return the teams
     */
    List<TeamSummary> teams(Actor actor);

    /**
     * One team.
     *
     * @param actor the actor
     * @param teamId the team id
     * @return the team
     */
    TeamSummary team(Actor actor, long teamId);

    /**
     * A team's members by name.
     *
     * @param actor the actor
     * @param teamId the team id
     * @return the members
     */
    List<Member> members(Actor actor, long teamId);

    /**
     * Creates a team; {@code CONFLICT} if the name is taken.
     *
     * @param actor the actor
     * @param name the name, 1 to 120 characters
     * @return the new team's id
     */
    long createTeam(Actor actor, String name);

    /**
     * Renames, deactivates or reactivates a team; {@code CONFLICT} if the new name is taken.
     *
     * @param actor the actor
     * @param teamId the team id
     * @param name the name, 1 to 120 characters
     * @param active whether the team is active
     */
    void updateTeam(Actor actor, long teamId, String name, boolean active);

    /**
     * Grants or changes a user's role in a team. {@code INVALID} if the user holds System Admin (a System Admin
     * holds no team role), is disabled, or the team is inactive.
     *
     * @param actor the actor
     * @param teamId the team id
     * @param userId the user's id
     * @param role the role
     */
    void assignRole(Actor actor, long teamId, long userId, TeamRole role);

    /**
     * Removes a user from a team; {@code NOT_FOUND} if the user is not a member.
     *
     * @param actor the actor
     * @param teamId the team id
     * @param userId the user's id
     */
    void revokeRole(Actor actor, long teamId, long userId);
}
