// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.closure;

/** Where a closure request stands ({@code CLOSURE_REQUEST.DECISION}, CHECK-constrained). */
public enum ClosureDecision {
    /** Waiting for the approver; the case is {@code PENDING_CLOSURE}. */
    PENDING,
    /** Approved: the case is {@code CLOSED}. */
    APPROVED,
    /** Rejected with a reason: the case is {@code OPEN} again. */
    REJECTED,
    /** Sent back for changes with a reason: the case is {@code OPEN} again and may be requested anew. */
    CHANGES_REQUESTED
}
