// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read access to users' authority, for {@code security} and the case modules. */
public interface UserDirectoryService {

    /**
     * Rebuilds a user's permissions from the database.
     *
     * @param userId the user's id
     * @return the permissions; {@link UserPermissions#none} for an unknown user
     */
    UserPermissions permissionsOf(long userId);

    /**
     * The enabled users holding Manager in a team.
     *
     * @param teamId the team id
     * @return their ids, ascending
     */
    List<Long> activeManagersOf(long teamId);

    /**
     * The enabled users holding Compliance Admin.
     *
     * @return their ids, ascending
     */
    List<Long> activeComplianceAdmins();

    /**
     * The enabled users holding any role in a team (Reader, Analyst or Manager).
     *
     * @param teamId the team id
     * @return their ids, ascending
     */
    List<Long> activeMembersOf(long teamId);

    /**
     * A user's display name, for showing who owns or did something.
     *
     * @param userId the user's id
     * @return the name, or empty for an unknown user
     */
    Optional<String> displayName(long userId);

    /**
     * Where to email a user, for {@code notification} only. Never logged or shown (Part B "Logging").
     *
     * @param userId the user's id
     * @return the address from the identity provider; empty for an unknown or disabled user or one without an address
     */
    Optional<String> mailAddress(long userId);

    /**
     * The users whose System Admin claim is withheld right now (IMPLEMENTATION_PLAN A.2 #18), cluster-wide and durable:
     * those whose latest sign-in recorded a {@code ROLE_CONFLICT} audit event, read from the audit trail. A later
     * sign-in without the conflict ({@code LOGIN} with no {@code ROLE_CONFLICT} after it) clears it. Callers decide
     * who may see the list (the System screen: System Admins and Compliance Admins).
     *
     * @return the conflicts, oldest first, each with the time it was last seen
     */
    List<RoleConflict> roleConflicts();

    /**
     * One team.
     *
     * @param teamId the team id
     * @return the team, or empty for an unknown team
     */
    Optional<TeamAdminService.TeamSummary> team(long teamId);

    /**
     * Every team, active or not, by name, for screens that list them all (the report schedules).
     *
     * @return the teams
     */
    List<TeamAdminService.TeamSummary> allTeams();

    /**
     * Display names of several users in one lookup, for lists.
     *
     * @param userIds the users' ids
     * @return each known user's name by id; unknown ids are absent
     */
    Map<Long, String> displayNames(Collection<Long> userIds);

    /**
     * Several teams in one lookup, for lists.
     *
     * @param teamIds the team ids
     * @return each known team by id; unknown ids are absent
     */
    Map<Long, TeamAdminService.TeamSummary> teams(Collection<Long> teamIds);

    /**
     * The enabled members who may own a team's cases (Analysts and Managers, never a System Admin), by name.
     *
     * @param teamId the team id
     * @return the candidate owners
     */
    List<TeamAdminService.UserChoice> eligibleOwners(long teamId);
}
