// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import org.jspecify.annotations.Nullable;

/** Writes the append-only audit trail. Every state-changing service calls it (Part E). */
public interface AuditRecorder {

    /**
     * Records an event synchronously in the caller's transaction, so the event is persisted exactly when the change
     * it describes is (BUILD_PLAN section 9). Adds the time from the application clock and, on a request thread, the
     * request id and client address.
     *
     * @param entry the event
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is active
     */
    void record(AuditEntry entry);

    /**
     * Produces the old and new values of a change from two snapshots of the same record type: only the components
     * whose values differ appear, in declaration order; components annotated {@link Redacted} appear only as an
     * HMAC and a length. A {@code null} {@code before} means creation (every component appears in the new values);
     * a {@code null} {@code after} means deletion. Component values are written as JSON numbers, booleans or
     * strings ({@code toString()} for anything else, so snapshots should be flat records of simple values).
     *
     * @param before the snapshot before the change, or {@code null}
     * @param after the snapshot after the change, or {@code null}
     * @param <T> the snapshot type
     * @return the redacted changes; {@link AuditChanges#none()} if nothing differs
     * @throws IllegalArgumentException if both are {@code null} or they are of different classes
     */
    <T extends Record> AuditChanges diff(@Nullable T before, @Nullable T after);
}
