// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

/** Where an outbox row stands (BUILD_PLAN §6 "Outbox dispatcher"); stored by name in {@code NOTIFICATION_OUTBOX}. */
enum OutboxStatus {
    /** Waiting for its next attempt, at {@code NEXT_ATTEMPT_AT}. */
    PENDING,
    /** Claimed by one dispatcher until {@code LOCKED_UNTIL}; the reaper returns it to pending after that. */
    SENDING,
    /** Handed to the mail server. */
    SENT,
    /** Every attempt failed; shown on the System screen (WP-25). */
    FAILED
}
