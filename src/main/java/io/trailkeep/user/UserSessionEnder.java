// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

/**
 * Port through which the {@code user} module signs a user out everywhere when it disables them. The module may not
 * call {@code security} (which calls {@code user}), so the security module's session service implements this port.
 */
public interface UserSessionEnder {

    /**
     * Deletes every application session of the user on every replica.
     *
     * @param userId {@code APP_USER.ID} (the sessions' principal name)
     * @return how many sessions were ended
     */
    int endSessionsOf(long userId);
}
