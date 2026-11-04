// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;
import java.util.Set;

/**
 * A signed-in user as other modules see it: no entity crosses the module boundary. Serializable because it rides on
 * the security principal.
 *
 * @param id {@code APP_USER.ID}
 * @param externalId the identity provider's subject
 * @param displayName the name to show
 * @param globalRoles the global roles in effect (after the System-Admin conflict rule)
 * @param teamRoles the user's role per team, keyed by team name
 */
public record UserAccount(
        long id, String externalId, String displayName, Set<GlobalRole> globalRoles, Map<String, TeamRole> teamRoles)
        implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Defensive copies: the record is immutable. */
    public UserAccount {
        globalRoles = Set.copyOf(globalRoles);
        teamRoles = Map.copyOf(teamRoles);
    }
}
