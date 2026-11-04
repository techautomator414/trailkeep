// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.Objects;

/**
 * Key of {@link AppUserGlobalRole}; the whole row is the key.
 *
 * @param userId the holder ({@code APP_USER.ID})
 * @param role the global role
 */
@Embeddable
record AppUserGlobalRoleId(
        @Column(name = "USER_ID", nullable = false) Long userId,

        @Enumerated(EnumType.STRING) @Column(name = "GLOBAL_ROLE", nullable = false, length = 16)
        GlobalRole role) {

    AppUserGlobalRoleId {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
    }
}
