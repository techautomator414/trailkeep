// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.util.List;

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
}
