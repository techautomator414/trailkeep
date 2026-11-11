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
 * with their run's staging; they are never changed.
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

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other
                || (other != null && getClass() == other.getClass() && key != null && key.equals(((StagedRow) other).key));
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass(), key);
    }
}
