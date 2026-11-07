// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

/** What an {@code ALERT_LEDGER} row records (BUILD_PLAN §6); the closed set of {@code CK_ALERT_LEDGER_TYPE}. */
enum AlertType {
    /** The case turned at risk, once per SLA cycle. */
    SLA_AT_RISK,
    /** The case turned overdue, once per SLA cycle. */
    SLA_OVERDUE,
    /** An overdue case's repeat reminder, once per period (WP-17b). */
    SLA_OVERDUE_REPEAT,
    /** An open update request's reminder, once per period (WP-17b). */
    UPDATE_REMINDER,
    /** A pending closure request's reminder, once per period (WP-17b). */
    CLOSURE_REMINDER
}
