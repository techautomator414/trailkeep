// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Writes a case's legacy change history into {@code CASE_IMPORTED_EVENT} (WP-26 part v, docs/design/importer.md §7.5,
 * §8.1). Called only by {@code importer} (ArchUnit), in the case's load transaction. The relaxation (R6 11, {@code
 * importedHistoryKeepsLegacyTimeAndRedacts}): a row carries the legacy event time and the legacy actor's name as
 * recorded, not the clock and a user.
 *
 * <p>Values are stored as text only for the {@link #PLAIN_FIELDS} of a non-{@link Kind#NOTE} event. Every other value,
 * including any field the mapping could not place ({@code other}) and every note, is redacted exactly as audit redacts
 * a {@link Redacted} value: the HMAC-SHA-256 under the audit key and the length, never the text. So a mapping mistake
 * hides a value rather than exposing one.
 */
public interface ImportedHistoryService {

    /** The canonical fields a legacy change may name (design §7.5). */
    Set<String> FIELDS =
            Set.of("status", "owner", "priority", "team", "due", "title", "description", "subject", "other");

    /** The fields whose values are stored as text; {@code title} is plain in {@code AUDIT_EVENT} too. */
    Set<String> PLAIN_FIELDS = Set.of("status", "owner", "priority", "team", "due", "title");

    /** The longest plain value stored; the importer truncates a longer one and reports it. */
    int MAX_VALUE = 1000;

    /** {@code CASE_IMPORTED_EVENT.WHO_NAME_SNAPSHOT}. */
    int MAX_WHO = 200;

    /** What kind of legacy change an event was. */
    enum Kind {
        FIELD_CHANGE,
        STATUS_CHANGE,
        ASSIGNMENT,
        NOTE,
        OTHER
    }

    /**
     * One legacy history event.
     *
     * @param caseId the imported case
     * @param at the legacy event time, already read in the source's declared zone
     * @param who the legacy actor's name as recorded, or {@code null} if the source has none
     * @param kind what kind of change it was
     * @param field one of {@link #FIELDS}, or {@code null} for an event about no field (a note, for example)
     * @param oldValue the value before, or {@code null}
     * @param newValue the value after, or {@code null}
     * @param runId the import run
     */
    record ImportedEvent(
            long caseId,
            Instant at,
            @Nullable String who,
            Kind kind,
            @Nullable String field,
            @Nullable String oldValue,
            @Nullable String newValue,
            long runId) {}

    /**
     * Whether an event's values are stored as text: those of a {@link #PLAIN_FIELDS} field of an event that is not a
     * {@link Kind#NOTE}; every other value is redacted.
     *
     * @param kind the event's kind
     * @param field its field, or {@code null}
     * @return true if plain
     */
    static boolean storesAsText(Kind kind, @Nullable String field) {
        return kind != Kind.NOTE && field != null && PLAIN_FIELDS.contains(field);
    }

    /**
     * Writes one event, redacting its values unless they are plain (see the class comment).
     *
     * @param event the event
     * @return the row's id
     * @throws IllegalArgumentException for a future time, a name over {@link #MAX_WHO} characters, a field outside
     *     {@link #FIELDS}, or a plain value over {@link #MAX_VALUE} characters
     */
    long record(ImportedEvent event);

    /**
     * Imported legacy history events as the database holds them (WP-26 part aa1, design §9): each one's canonical
     * written form ({@code WrittenForm}), which the importer hashes into its ledger when it writes it and again in
     * {@code reconcile}. Pending writes of the caller's transaction are flushed first, so they are read as stored.
     *
     * @param ids the events, any number
     * @return each existing one's form, by id
     */
    Map<Long, String> writtenEvents(Collection<Long> ids);
}
