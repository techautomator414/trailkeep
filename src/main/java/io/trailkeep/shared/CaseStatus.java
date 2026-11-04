// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared;

/**
 * A case's lifecycle state (BUILD_PLAN §5.2). Lives in {@code shared} because the permission matrix ({@code
 * security}) decides on it before the case module (WP-11) exists; {@code casefile} persists it by name.
 */
public enum CaseStatus {
    /** Being worked. */
    OPEN,
    /** A closure request awaits a decision; reassignment is blocked. */
    PENDING_CLOSURE,
    /** Closed; a Manager+ may reopen it. */
    CLOSED,
    /** Past retention: read-only, not reopenable. */
    ARCHIVED
}
