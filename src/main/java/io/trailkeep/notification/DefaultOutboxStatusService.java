// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads the outbox's backlog from {@code NOTIFICATION_OUTBOX}. */
@Service
class DefaultOutboxStatusService implements OutboxStatusService {

    private final OutboxJdbcDao outbox;

    DefaultOutboxStatusService(OutboxJdbcDao outbox) {
        this.outbox = outbox;
    }

    @Override
    @Transactional(readOnly = true)
    public Backlog backlog() {
        return new Backlog(
                outbox.count(OutboxStatus.PENDING), outbox.count(OutboxStatus.FAILED), outbox.oldestPending());
    }
}
