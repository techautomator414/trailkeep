// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

/**
 * A role that applies across teams (BUILD_PLAN §3.1), mapped from identity-provider groups. Persisted by name in
 * {@code APP_USER_GLOBAL_ROLE.GLOBAL_ROLE}. A user holding {@link #SYSTEM_ADMIN} holds no team role.
 */
public enum GlobalRole {
    /** Manager across all teams, plus case types, SLA policies, retention, legal hold and quarantine release. */
    COMPLIANCE_ADMIN,
    /** User and team administration and system health; never case content. */
    SYSTEM_ADMIN
}
