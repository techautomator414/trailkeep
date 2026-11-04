// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

/**
 * A global role held by a user ({@code APP_USER_GLOBAL_ROLE}). Rows are inserted and deleted, never updated: the
 * runtime user has no UPDATE on this table.
 */
@Entity
@Table(name = "APP_USER_GLOBAL_ROLE")
class AppUserGlobalRole {

    @EmbeddedId
    private AppUserGlobalRoleId id;

    /** For JPA. */
    protected AppUserGlobalRole() {
        this.id = new AppUserGlobalRoleId(0L, GlobalRole.COMPLIANCE_ADMIN);
    }

    AppUserGlobalRole(AppUserGlobalRoleId id) {
        this.id = id;
    }

    AppUserGlobalRoleId getId() {
        return id;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof AppUserGlobalRole role && id.equals(role.id));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "AppUserGlobalRole[" + id + "]";
    }
}
