// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

/** Where a case stands against its SLA (BUILD_PLAN §6); stored by name in {@code CASE_SLA.SLA_STATE}. */
public enum SlaState {
    /** Before the at-risk point. */
    ON_TRACK,
    /** Past the at-risk point, before the due date. */
    AT_RISK,
    /** Past the due date and still open. */
    OVERDUE,
    /** Closed on or before the due date. */
    MET,
    /** Closed after the due date. */
    BREACHED,
    /** No SLA applies, for example an imported closed case; both dates are null. */
    NOT_APPLICABLE
}
