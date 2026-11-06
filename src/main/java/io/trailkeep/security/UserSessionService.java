// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import io.trailkeep.user.UserSessionEnder;

/**
 * Ends sessions on every replica. Disabling a user ({@code UserAdminService}, through the {@link UserSessionEnder}
 * port) calls {@link #endSessionsOf(long)} so the user is signed out at once;
 * independently, the next request of a disabled user ends their session anyway.
 */
public interface UserSessionService extends UserSessionEnder {

    /**
     * Deletes every application session of the user and their back-channel registry rows.
     *
     * @param userId {@code APP_USER.ID} (the sessions' principal name)
     * @return how many sessions were ended
     */
    @Override
    int endSessionsOf(long userId);
}
