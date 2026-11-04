// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.time.Instant;

/**
 * The identity provider claimed {@link GlobalRole#SYSTEM_ADMIN} for a user who holds team roles, so the global role
 * was not applied (IMPLEMENTATION_PLAN A.2 #18). Another System Admin removes the team roles first.
 *
 * @param userId {@code APP_USER.ID}
 * @param detectedAt the login at which the conflict was last seen
 */
public record RoleConflict(long userId, Instant detectedAt) {}
