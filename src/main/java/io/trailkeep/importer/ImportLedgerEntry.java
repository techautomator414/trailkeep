// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

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
import org.jspecify.annotations.Nullable;

/**
 * One legacy row the load wrote (V17 {@code IMPORT_LEDGER}, design §5, §8.2), saved in the same transaction as its
 * domain rows: a key is loaded at most once across runs, and a crashed load resumes at the first row it does not hold.
 * Insert-only.
 */
@Entity
@Table(name = "IMPORT_LEDGER")
class ImportLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "importLedgerSeq")
    @SequenceGenerator(name = "importLedgerSeq", sequenceName = "SEQ_IMPORT_LEDGER", allocationSize = 50)
    @Column(name = "ID", nullable = false, updatable = false)
    private @Nullable Long id;

    @Column(name = "SOURCE_ID", nullable = false, updatable = false, length = 16)
    private String sourceId = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "ENTITY", nullable = false, updatable = false, length = 16)
    private ImportIssue.Subject entity = ImportIssue.Subject.CASE;

    @Column(name = "LEGACY_KEY", nullable = false, updatable = false, length = 100)
    private String legacyKey = "";

    @Column(name = "CASE_ID", nullable = false, updatable = false)
    private long caseId;

    @Column(name = "TARGET_ID", nullable = false, updatable = false)
    private long targetId;

    @Column(name = "ROW_HASH", nullable = false, updatable = false, length = 64)
    private String rowHash = "";

    @Column(name = "MAPPING_HASH", nullable = false, updatable = false, length = 64)
    private String mappingHash = "";

    @Column(name = "STATUS", updatable = false, length = 16)
    private @Nullable String status;

    @Column(name = "CLOSED_AT", updatable = false)
    private @Nullable Instant closedAt;

    @Column(name = "RUN_ID", nullable = false, updatable = false)
    private long runId;

    @Column(name = "LOADED_AT", nullable = false, updatable = false)
    private Instant loadedAt = Instant.EPOCH;

    /** For JPA. */
    protected ImportLedgerEntry() {}

    /**
     * A loaded case: its canonical status and closing time as loaded (R10), so a delta run sees a closure in the
     * source without comparing row hashes.
     *
     * @param run the run that loaded it, with the source id and mapping hash it was loaded under
     * @param legacyKey its canonical key
     * @param rowHash its staged row's keyed hash
     * @param caseId the Trailkeep case
     * @param status {@code OPEN} or {@code CLOSED}
     * @param closedAt when it was closed, or {@code null}
     * @param at when it was loaded
     */
    static ImportLedgerEntry ofCase(
            Loaded run,
            String legacyKey,
            String rowHash,
            long caseId,
            String status,
            @Nullable Instant closedAt,
            Instant at) {
        ImportLedgerEntry entry = new ImportLedgerEntry();
        entry.sourceId = run.sourceId();
        entry.entity = ImportIssue.Subject.CASE;
        entry.legacyKey = legacyKey;
        entry.caseId = caseId;
        entry.targetId = caseId;
        entry.rowHash = rowHash;
        entry.mappingHash = run.mappingHash();
        entry.status = status;
        entry.closedAt = closedAt;
        entry.runId = run.runId();
        entry.loadedAt = at;
        return entry;
    }

    /**
     * A loaded child of a case: a comment, attachment or history event.
     *
     * @param run the run that loaded it, with the source id and mapping hash it was loaded under
     * @param entity what it is
     * @param legacyKey its canonical key
     * @param rowHash its staged row's keyed hash
     * @param caseId its Trailkeep case
     * @param targetId the row written, the first when it was split
     * @param at when it was loaded
     */
    static ImportLedgerEntry ofChild(
            Loaded run,
            ImportIssue.Subject entity,
            String legacyKey,
            String rowHash,
            long caseId,
            long targetId,
            Instant at) {
        ImportLedgerEntry entry = ofCase(run, legacyKey, rowHash, caseId, "", null, at);
        entry.entity = entity;
        entry.targetId = targetId;
        entry.status = null;
        return entry;
    }

    /**
     * What every ledger row of one load shares.
     *
     * @param runId the run
     * @param sourceId its {@code source.id}
     * @param mappingHash the mapping hash it loads under
     */
    record Loaded(long runId, String sourceId, String mappingHash) {}

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof ImportLedgerEntry that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return ImportLedgerEntry.class.hashCode();
    }
}
