// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

/**
 * A user's membership of a team with one {@link TeamRole} ({@code APP_USER_TEAM}). Holds the two ids rather than
 * associations: membership is read by user (login, permissions) or by team (admin), never navigated.
 */
@Entity
@Table(name = "APP_USER_TEAM")
class AppUserTeam {

    @EmbeddedId
    private AppUserTeamId id;

    @Enumerated(EnumType.STRING)
    @Column(name = "ROLE_IN_TEAM", nullable = false, length = 10)
    private TeamRole role;

    /** For JPA. */
    protected AppUserTeam() {
        this.id = new AppUserTeamId(0L, 0L);
        this.role = TeamRole.READER;
    }

    AppUserTeam(AppUserTeamId id, TeamRole role) {
        this.id = id;
        this.role = role;
    }

    AppUserTeamId getId() {
        return id;
    }

    TeamRole getRole() {
        return role;
    }

    void changeRole(TeamRole newRole) {
        this.role = newRole;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof AppUserTeam membership && id.equals(membership.id));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "AppUserTeam[" + id + ", role=" + role + "]";
    }
}
