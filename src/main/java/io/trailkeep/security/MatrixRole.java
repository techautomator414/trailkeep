// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

/** The actor's effective role for one team: the {@code role} column of the matrix. */
enum MatrixRole {
    SYSTEM_ADMIN,
    COMPLIANCE_ADMIN,
    MANAGER,
    ANALYST,
    READER,
    NONE
}
