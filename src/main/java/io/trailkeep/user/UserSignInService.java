// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.util.List;
import java.util.Set;

/** Provisions users from identity-provider logins and applies the global-role rules. Called by {@code security}. */
public interface UserSignInService {

    /**
     * Upserts the user from the provider's claims, records the login and replaces their global roles with the
     * claimed ones, except that {@link GlobalRole#SYSTEM_ADMIN} is withheld from a user who holds any team role
     * (the conflict is logged and listed by {@link #roleConflicts()}). A disabled user is refused and nothing is
     * updated.
     *
     * @param identity who the provider says signed in
     * @param claimedRoles the global roles mapped from the provider's group claim
     * @return the outcome
     */
    SignInOutcome signIn(ExternalIdentity identity, Set<GlobalRole> claimedRoles);

    /**
     * The System-Admin conflicts seen at the latest login of each user, for the System screen (WP-25). Held by this
     * replica until audit events (WP-06) record them durably.
     *
     * @return the conflicts, oldest first
     */
    List<RoleConflict> roleConflicts();
}
