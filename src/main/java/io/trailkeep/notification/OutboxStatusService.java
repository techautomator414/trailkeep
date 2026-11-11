// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The outbox as the System screen shows it (WP-25): how much mail is waiting and how much failed for good. The same
 * counts are the {@code trailkeep.outbox.pending} and {@code trailkeep.outbox.failed} gauges.
 */
public interface OutboxStatusService {

    /**
     * The outbox's backlog.
     *
     * @param pending mails waiting to be sent, including those waiting for a retry
     * @param failed mails that failed for the last time
     * @param oldestPendingAt when the oldest waiting mail was queued, or {@code null} when none is waiting
     */
    record Backlog(long pending, long failed, @Nullable Instant oldestPendingAt) {}

    /**
     * Reads the backlog now (three indexed queries, not cached).
     *
     * @return the backlog
     */
    Backlog backlog();
}
