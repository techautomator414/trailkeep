// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One audit event to record.
 *
 * @param actorId the acting user's id, or {@code null} for a job or the system
 * @param actorName the acting user's display name at the time, kept because the user row may change or disappear
 * @param origin what initiated the change
 * @param caseId the case the event belongs to; required for every case-rooted event so case-scoped reads find it,
 *     {@code null} for admin, login and export events
 * @param entityType the kind of entity changed, such as {@code TEAM} or {@code CASE} (at most 40 characters)
 * @param entityId the changed entity's id, if it has one
 * @param action what happened, such as {@code CREATE} or {@code ROLE_GRANTED} (at most 40 characters)
 * @param changes the redacted old and new values
 */
public record AuditEntry(
        @Nullable Long actorId,
        @Nullable String actorName,
        AuditOrigin origin,
        @Nullable Long caseId,
        String entityType,
        @Nullable Long entityId,
        String action,
        AuditChanges changes) {

    private static final int MAX_CODE_LENGTH = 40;
    private static final int MAX_NAME_LENGTH = 200;

    /** Validates the lengths the table enforces, so a bad entry fails in the caller rather than at flush. */
    public AuditEntry {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(changes, "changes");
        requireCode(entityType, "entityType");
        requireCode(action, "action");
        if (actorName != null && actorName.length() > MAX_NAME_LENGTH) {
            // Never cut between the two halves of a surrogate pair.
            int end = Character.isHighSurrogate(actorName.charAt(MAX_NAME_LENGTH - 1))
                    ? MAX_NAME_LENGTH - 1
                    : MAX_NAME_LENGTH;
            actorName = actorName.substring(0, end);
        }
    }

    private static void requireCode(String value, String name) {
        if (value.isBlank() || value.length() > MAX_CODE_LENGTH) {
            throw new IllegalArgumentException(name + " must be 1 to " + MAX_CODE_LENGTH + " characters");
        }
    }
}
