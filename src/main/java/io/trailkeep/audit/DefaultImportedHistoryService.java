// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import io.trailkeep.audit.SnapshotDiff.Redaction;
import jakarta.persistence.EntityManager;
import java.time.InstantSource;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ImportedHistoryService}: one {@code CASE_IMPORTED_EVENT} row per event, in the caller's transaction ({@link
 * Propagation#MANDATORY}, as {@link JpaAuditRecorder}), redacted through the same {@link SnapshotDiff} key as audit.
 * The row is not itself audited: it is history, and the case it belongs to is audited as {@code CASE_IMPORTED}.
 */
@Service
class DefaultImportedHistoryService implements ImportedHistoryService {

    private final EntityManager entityManager;
    private final SnapshotDiff snapshotDiff;
    private final InstantSource clock;

    DefaultImportedHistoryService(EntityManager entityManager, SnapshotDiff snapshotDiff, InstantSource clock) {
        this.entityManager = entityManager;
        this.snapshotDiff = snapshotDiff;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long record(ImportedEvent event) {
        validate(event);
        String field = event.field();
        CaseImportedEvent row;
        if (event.kind() != Kind.NOTE && field != null && PLAIN_FIELDS.contains(field)) {
            if (tooLong(event.oldValue()) || tooLong(event.newValue())) {
                throw new IllegalArgumentException("an imported plain value is too long");
            }
            row = CaseImportedEvent.plain(event);
        } else {
            row = CaseImportedEvent.redacted(event, redact(event.oldValue()), redact(event.newValue()));
        }
        entityManager.persist(row);
        return Objects.requireNonNull(row.getId(), "persisted");
    }

    /** The rules every event keeps, whatever its values. */
    private void validate(ImportedEvent event) {
        if (event.at().isAfter(clock.instant())) {
            throw new IllegalArgumentException("an imported event cannot be in the future");
        }
        String who = event.who();
        if (who != null && who.length() > MAX_WHO) {
            throw new IllegalArgumentException("an imported event's actor name is too long");
        }
        String field = event.field();
        if (field != null && !FIELDS.contains(field)) {
            throw new IllegalArgumentException("an imported event names a field outside the canonical set");
        }
    }

    private static boolean tooLong(@Nullable String value) {
        return value != null && value.length() > MAX_VALUE;
    }

    private @Nullable Redaction redact(@Nullable String value) {
        return value == null ? null : snapshotDiff.redact(value);
    }
}
