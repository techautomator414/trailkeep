// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

/** The result of {@link UserSignInService#signIn}. */
public sealed interface SignInOutcome {

    /**
     * The user may sign in.
     *
     * @param account the provisioned account
     */
    record SignedIn(UserAccount account) implements SignInOutcome {}

    /**
     * The user may not sign in: the account is disabled in this application (BUILD_PLAN §3.2).
     *
     * @param userId {@code APP_USER.ID} of the refused account
     */
    record Disabled(long userId) implements SignInOutcome {}
}
