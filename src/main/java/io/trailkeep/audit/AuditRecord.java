// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A recorded audit event as readers see it. The client address is deliberately left out of this read model; it is
 * kept in the table for investigations and audit export.
 *
 * @param at when the event was recorded (application clock, UTC)
 * @param actorId the acting user's id, or {@code null}
 * @param actorName the acting user's name at the time, or {@code null}
 * @param origin what initiated the change
 * @param caseId the case the event belongs to, or {@code null}
 * @param entityType the kind of entity changed
 * @param entityId the changed entity's id, or {@code null}
 * @param action what happened
 * @param changes the redacted old and new values
 * @param requestId the correlation id of the originating request, or {@code null}
 */
public record AuditRecord(
        Instant at,
        @Nullable Long actorId,
        @Nullable String actorName,
        AuditOrigin origin,
        @Nullable Long caseId,
        String entityType,
        @Nullable Long entityId,
        String action,
        AuditChanges changes,
        @Nullable String requestId) {}
