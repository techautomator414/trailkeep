// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

/** A user's role within one team (BUILD_PLAN §3.1). Persisted by name in {@code APP_USER_TEAM.ROLE_IN_TEAM}. */
public enum TeamRole {
    /** Reads non-restricted cases of the team and runs reports. */
    READER,
    /** Works cases of the team: create, edit, comment, attach, request update and closure. */
    ANALYST,
    /** Analyst plus approve, reject, reopen, reassign and restricted cases of the team. */
    MANAGER
}
