// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import io.trailkeep.user.TeamAdminService.Actor;
import java.util.List;

/**
 * Disabling and re-enabling users, for System Admins only (BUILD_PLAN §4.6, §9 Identity). Every change is audited.
 * Every method throws {@link TeamAdminException}: {@code FORBIDDEN} unless the actor holds System Admin, {@code
 * NOT_FOUND} for an unknown user.
 */
public interface UserAdminService {

    /**
     * A user as the admin screen lists it.
     *
     * @param userId the user's id
     * @param displayName the user's name
     * @param disabled whether the user is disabled
     */
    record UserRow(long userId, String displayName, boolean disabled) {}

    /**
     * All users by name.
     *
     * @param actor the actor
     * @return the users
     */
    List<UserRow> users(Actor actor);

    /**
     * Disables a user and, in the same transaction and after the flag is written, ends all of their sessions on every
     * replica. Audited as {@code USER_DISABLED} with the number of sessions ended. Disabling a disabled user changes
     * nothing; {@code INVALID} if the actor disables themselves.
     *
     * @param actor the actor
     * @param userId the user's id
     */
    void disable(Actor actor, long userId);

    /**
     * Enables a user; audited as {@code USER_ENABLED}. Enabling an enabled user changes nothing.
     *
     * @param actor the actor
     * @param userId the user's id
     */
    void enable(Actor actor, long userId);
}
