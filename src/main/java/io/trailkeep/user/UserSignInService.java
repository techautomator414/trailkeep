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
     * (the conflict is logged, audited as {@code ROLE_CONFLICT} and listed by {@link #roleConflicts()}). A disabled
     * user is refused and nothing is updated except the {@code LOGIN_REFUSED} audit event. Every change is audited
     * in the same transaction: {@code USER_PROVISIONED}, {@code LOGIN}, {@code GLOBAL_ROLES_CHANGED}.
     *
     * @param identity who the provider says signed in
     * @param claimedRoles the global roles mapped from the provider's group claim
     * @return the outcome
     */
    SignInOutcome signIn(ExternalIdentity identity, Set<GlobalRole> claimedRoles);

    /**
     * The System-Admin conflicts seen at the latest login of each user on this replica, a quick in-memory view. The
     * durable, cluster-wide record is the {@code ROLE_CONFLICT} audit event, which the System screen (WP-25) reads.
     *
     * @return the conflicts, oldest first
     */
    List<RoleConflict> roleConflicts();
}
