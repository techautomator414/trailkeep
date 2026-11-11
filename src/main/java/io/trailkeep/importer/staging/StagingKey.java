// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer.staging;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

/**
 * Key of every staging row: its run and its canonical key (design §5 "Keys", at most 100 characters with the source
 * id).
 *
 * @param runId the run ({@code IMPORT_RUN.ID})
 * @param legacyKey the canonical key
 */
@Embeddable
public record StagingKey(
        @Column(name = "RUN_ID", nullable = false) Long runId,
        @Column(name = "LEGACY_KEY", nullable = false, length = 100) String legacyKey) {

    /** Checks both parts are present. */
    public StagingKey {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(legacyKey, "legacyKey");
    }
}
