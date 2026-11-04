// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

/**
 * Ends sessions on every replica. Disabling a user (WP-09) calls {@link #endSessionsOf(long)} so the user is signed
 * out at once; independently, the next request of a disabled user ends their session anyway.
 */
public interface UserSessionService {

    /**
     * Deletes every application session of the user and their back-channel registry rows.
     *
     * @param userId {@code APP_USER.ID} (the sessions' principal name)
     * @return how many sessions were ended
     */
    int endSessionsOf(long userId);
}
