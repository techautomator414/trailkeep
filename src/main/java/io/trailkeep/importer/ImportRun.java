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
import jakarta.persistence.Version;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.DynamicUpdate;
import org.jspecify.annotations.Nullable;

/**
 * One import or owner-relink run (V10 {@code IMPORT_RUN}, design §3 and §5). It holds codes, hashes and counts, never a
 * legacy value.
 *
 * <p>Two database users change it, each only its own columns (R__grants.sql): the runtime user the approval, the
 * import user the status, hashes, scanning mode, counts and staging expiry. {@link DynamicUpdate} keeps each UPDATE to
 * the columns that changed, so neither writer's statement names a column only the other may write.
 */
@Entity
@Table(name = "IMPORT_RUN")
@DynamicUpdate
class ImportRun {

    /** What a run does. */
    enum Kind {
        /** Extract, dry run, sign-off, load and reconcile. */
        IMPORT,
        /** Reassign default-owned open cases to people who have since signed in (design §7.3). */
        RELINK
    }

    /** Where a run is in its lifecycle (design §3). */
    enum Status {
        REQUESTED,
        VALIDATED,
        EXTRACTED,
        DRY_RUN,
        APPROVED,
        LOADING,
        LOADED,
        RECONCILED,
        FAILED
    }

    /** The attachment scanning mode in force at the dry run, which the load must still find (design §4.3, R1). */
    enum ScanMode {
        CLAMD,
        EXTERNAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "importRunSeq")
    @SequenceGenerator(name = "importRunSeq", sequenceName = "SEQ_IMPORT_RUN", allocationSize = 50)
    @Column(name = "ID", nullable = false, updatable = false)
    private @Nullable Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "KIND", nullable = false, updatable = false, length = 8)
    private Kind kind = Kind.IMPORT;

    @Column(name = "SOURCE_ID", nullable = false, updatable = false, length = 16)
    private String sourceId = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", nullable = false, length = 16)
    private Status status = Status.REQUESTED;

    @Column(name = "REQUESTED_BY", nullable = false, updatable = false)
    private long requestedBy;

    @Column(name = "REQUESTED_AT", nullable = false, updatable = false)
    private Instant requestedAt = Instant.EPOCH;

    @Column(name = "APPROVED_BY")
    private @Nullable Long approvedBy;

    @Column(name = "APPROVED_AT")
    private @Nullable Instant approvedAt;

    @Column(name = "ACKNOWLEDGED_WARNINGS", length = 1000)
    private @Nullable String acknowledgedWarnings;

    @Column(name = "MAPPING_HASH", length = 64)
    private @Nullable String mappingHash;

    @Column(name = "SOURCE_FINGERPRINT", length = 128)
    private @Nullable String sourceFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "SCAN_MODE", length = 8)
    private @Nullable ScanMode scanMode;

    @Column(name = "ROWS_STAGED", nullable = false)
    private long rowsStaged;

    @Column(name = "ROWS_LOADED", nullable = false)
    private long rowsLoaded;

    @Column(name = "STAGING_EXPIRES_AT")
    private @Nullable Instant stagingExpiresAt;

    @Column(name = "STAGING_PURGED_AT")
    private @Nullable Instant stagingPurgedAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt = Instant.EPOCH;

    @Version
    @Column(name = "VERSION", nullable = false)
    private int version;

    /** For JPA. */
    protected ImportRun() {}

    /**
     * A run as its request creates it.
     *
     * @param kind what it does
     * @param sourceId the mapping's {@code source.id}
     * @param requestedBy the {@code APP_USER} id of whoever requested it
     * @param at when
     */
    ImportRun(Kind kind, String sourceId, long requestedBy, Instant at) {
        this.kind = kind;
        this.sourceId = sourceId;
        this.requestedBy = requestedBy;
        this.requestedAt = at;
        this.updatedAt = at;
    }

    long getId() {
        return Objects.requireNonNull(id, "not persisted");
    }

    Kind getKind() {
        return kind;
    }

    String getSourceId() {
        return sourceId;
    }

    Status getStatus() {
        return status;
    }

    boolean isApproved() {
        return approvedBy != null;
    }

    @Nullable
    ScanMode getScanMode() {
        return scanMode;
    }

    long getRowsStaged() {
        return rowsStaged;
    }

    long getRowsLoaded() {
        return rowsLoaded;
    }

    @Nullable
    Instant getStagingExpiresAt() {
        return stagingExpiresAt;
    }

    @Nullable
    Instant getStagingPurgedAt() {
        return stagingPurgedAt;
    }

    /** Whether {@code validate-mapping} may run: an import run nobody has extracted for yet. */
    boolean awaitsMapping() {
        // Once an extract has started, the run is that mapping and that snapshot: a new mapping needs a new run.
        return kind == Kind.IMPORT
                && (status == Status.REQUESTED || (status == Status.VALIDATED && sourceFingerprint == null));
    }

    /**
     * Whether the run was validated with this mapping file and these lookup files.
     *
     * @param hash their mapping hash ({@link LookupFiles#hash})
     * @return true if it is the validated file
     */
    boolean hasMappingHash(String hash) {
        // Constant time, like every hash comparison here (SpotBugs UNSAFE_HASH_EQUALS); the hash is not a secret.
        return mappingHash != null
                && MessageDigest.isEqual(
                        hash.getBytes(StandardCharsets.US_ASCII), mappingHash.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * An extract starts or starts again (WP-26 part k1b): it records what the source was read at, and the staging
     * expiry the first time, so that abandoned staging expires too.
     *
     * @param fingerprint the source's fingerprint
     * @param expiresAt when staging expires, unless an earlier attempt set it
     * @param at now
     * @return true when an earlier attempt read another fingerprint, so its staging must go
     */
    boolean extractStarting(String fingerprint, Instant expiresAt, Instant at) {
        boolean otherPoint = sourceFingerprint != null && !sourceFingerprint.equals(fingerprint);
        if (sourceFingerprint == null || otherPoint) {
            rowsStaged = 0;
        }
        this.sourceFingerprint = fingerprint;
        this.stagingExpiresAt = Objects.requireNonNullElse(stagingExpiresAt, expiresAt);
        this.updatedAt = at;
        return otherPoint;
    }

    /**
     * An entity's extract finished.
     *
     * @param rows how many rows it staged
     * @param at now
     */
    void entityStaged(long rows, Instant at) {
        this.rowsStaged += rows;
        this.updatedAt = at;
    }

    /**
     * Every entity is staged.
     *
     * @param at now
     */
    void extracted(Instant at) {
        this.status = Status.EXTRACTED;
        this.updatedAt = at;
    }

    /**
     * Records a mapping file that passed {@code validate-mapping}.
     *
     * @param hash the mapping hash over the file and its lookup files ({@link LookupFiles#hash}), in hex
     * @param at when
     */
    void mappingValidated(String hash, Instant at) {
        this.status = Status.VALIDATED;
        this.mappingHash = hash;
        this.updatedAt = at;
    }

    /**
     * Records a mapping file that failed {@code validate-mapping}: whatever passed before no longer stands, so the run
     * is back to {@code REQUESTED} with no mapping hash.
     *
     * @param at when
     */
    void mappingRejected(Instant at) {
        this.status = Status.REQUESTED;
        this.mappingHash = null;
        this.updatedAt = at;
    }

    /**
     * Records that the run's staging was deleted.
     *
     * @param at when
     */
    void stagingPurged(Instant at) {
        this.stagingPurgedAt = at;
        this.updatedAt = at;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof ImportRun that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return ImportRun.class.hashCode();
    }
}
