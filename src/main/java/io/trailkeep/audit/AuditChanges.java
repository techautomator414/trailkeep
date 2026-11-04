// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import org.jspecify.annotations.Nullable;

/**
 * The old and new values of an audited change as JSON objects, already redacted. Produced by
 * {@link AuditRecorder#diff}; callers do not build the JSON themselves.
 *
 * @param oldValuesJson the changed fields before the change, or {@code null} on creation or when nothing is recorded
 * @param newValuesJson the changed fields after the change, or {@code null} on deletion or when nothing is recorded
 */
public record AuditChanges(
        @Nullable String oldValuesJson, @Nullable String newValuesJson) {

    private static final AuditChanges NONE = new AuditChanges(null, null);

    /**
     * No old or new values, for events such as a login or a restricted-case view.
     *
     * @return the empty change set
     */
    public static AuditChanges none() {
        return NONE;
    }
}
