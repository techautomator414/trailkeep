// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer.staging;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.MappedSuperclass;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What every staging row has (design §5): its key and the keyed HMAC-SHA-256 of the canonical row under {@code
 * import.hash-key}, in hex. Staging rows are written by the extract and read by the dry run and the load, then deleted
 * with their run's staging; they are never changed, so object identity within a persistence context is all the
 * equality they need.
 */
@MappedSuperclass
public abstract class StagedRow {

    @EmbeddedId
    private @Nullable StagingKey key;

    @Column(name = "ROW_HASH", nullable = false, updatable = false, length = 64)
    private String rowHash = "";

    /** For JPA. */
    protected StagedRow() {}

    /**
     * A row as the extract writes it.
     *
     * @param key its run and canonical key
     * @param rowHash the keyed hash of its canonical row
     */
    protected StagedRow(StagingKey key, String rowHash) {
        this.key = key;
        this.rowHash = rowHash;
    }

    /**
     * The row's key.
     *
     * @return its run and canonical key
     */
    public StagingKey getKey() {
        return Objects.requireNonNull(key, "not loaded");
    }

    /**
     * The keyed hash of the canonical row.
     *
     * @return the hex HMAC
     */
    public String getRowHash() {
        return rowHash;
    }
}
