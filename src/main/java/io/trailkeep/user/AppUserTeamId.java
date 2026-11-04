// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

/**
 * Key of {@link AppUserTeam}: one membership per user and team.
 *
 * @param userId the member ({@code APP_USER.ID})
 * @param teamId the team ({@code TEAM.ID})
 */
@Embeddable
record AppUserTeamId(
        @Column(name = "USER_ID", nullable = false) Long userId,
        @Column(name = "TEAM_ID", nullable = false) Long teamId) {

    AppUserTeamId {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(teamId, "teamId");
    }
}
