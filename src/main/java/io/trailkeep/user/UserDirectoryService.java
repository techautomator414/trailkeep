// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.util.List;
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
     * A user's display name, for showing who owns or did something.
     *
     * @param userId the user's id
     * @return the name, or empty for an unknown user
     */
    Optional<String> displayName(long userId);

    /**
     * One team.
     *
     * @param teamId the team id
     * @return the team, or empty for an unknown team
     */
    Optional<TeamAdminService.TeamSummary> team(long teamId);
}
