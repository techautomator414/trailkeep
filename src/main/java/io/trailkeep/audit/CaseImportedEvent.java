// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import io.trailkeep.audit.ImportedHistoryService.Kind;
import io.trailkeep.audit.SnapshotDiff.Redaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code CASE_IMPORTED_EVENT} (V16): a change from a case's legacy history, insert-only ({@link
 * Immutable}; the import user has no UPDATE or DELETE grant). {@code CASE_ID} and {@code RUN_ID} are plain ids, as in
 * {@link AuditEvent}. Each value is either plain text or a {@link Redaction}, never both. Never leaves this module.
 */
@Entity
@Immutable
@Table(name = "CASE_IMPORTED_EVENT")
class CaseImportedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "caseImportedEventSeq")
    @SequenceGenerator(name = "caseImportedEventSeq", sequenceName = "SEQ_CASE_IMPORTED_EVENT", allocationSize = 50)
    @Column(name = "ID", nullable = false)
    private @Nullable Long id;

    @Column(name = "CASE_ID", nullable = false)
    private long caseId;

    @Column(name = "AT_TS", nullable = false)
    private Instant at = Instant.EPOCH;

    @Column(name = "WHO_NAME_SNAPSHOT", length = ImportedHistoryService.MAX_WHO)
    private @Nullable String who;

    @Enumerated(EnumType.STRING)
    @Column(name = "KIND", nullable = false, length = 16)
    private Kind kind = Kind.OTHER;

    @Column(name = "FIELD_NAME", length = 40)
    private @Nullable String field;

    @Column(name = "OLD_VALUE", length = ImportedHistoryService.MAX_VALUE)
    private @Nullable String oldValue;

    @Column(name = "NEW_VALUE", length = ImportedHistoryService.MAX_VALUE)
    private @Nullable String newValue;

    @Column(name = "OLD_HMAC", length = 64)
    private @Nullable String oldHmac;

    @Column(name = "NEW_HMAC", length = 64)
    private @Nullable String newHmac;

    @Column(name = "OLD_LENGTH")
    private @Nullable Integer oldLength;

    @Column(name = "NEW_LENGTH")
    private @Nullable Integer newLength;

    @Column(name = "RUN_ID", nullable = false)
    private long runId;

    /** For JPA. */
    protected CaseImportedEvent() {}

    /**
     * A row whose values are plain text.
     *
     * @param event the event
     * @return the row
     */
    static CaseImportedEvent plain(ImportedHistoryService.ImportedEvent event) {
        CaseImportedEvent row = new CaseImportedEvent(event);
        row.oldValue = event.oldValue();
        row.newValue = event.newValue();
        return row;
    }

    /**
     * A row whose values are redacted; a null value stays null.
     *
     * @param event the event
     * @param oldValue the redaction of the value before, or {@code null}
     * @param newValue the redaction of the value after, or {@code null}
     * @return the row
     */
    static CaseImportedEvent redacted(
            ImportedHistoryService.ImportedEvent event, @Nullable Redaction oldValue, @Nullable Redaction newValue) {
        CaseImportedEvent row = new CaseImportedEvent(event);
        if (oldValue != null) {
            row.oldHmac = oldValue.hmac();
            row.oldLength = oldValue.length();
        }
        if (newValue != null) {
            row.newHmac = newValue.hmac();
            row.newLength = newValue.length();
        }
        return row;
    }

    private CaseImportedEvent(ImportedHistoryService.ImportedEvent event) {
        this.caseId = event.caseId();
        this.at = event.at();
        this.who = event.who();
        this.kind = event.kind();
        this.field = event.field();
        this.runId = event.runId();
    }

    @Nullable
    Long getId() {
        return id;
    }
}
