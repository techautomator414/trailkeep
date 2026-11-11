// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

/**
 * One aggregate of a run that the sign-off screen renders (V10 {@code IMPORT_RUN_SUMMARY}, design §3 R8): a metric, an
 * optional dimension such as a team id, an SLA state, a scan status or a role, and a whole number. Numbers only, never
 * field values. The import user rewrites a run's rows on each dry run; the runtime user reads them.
 */
@Entity
@Table(name = "IMPORT_RUN_SUMMARY")
class ImportRunSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "importRunSummarySeq")
    @SequenceGenerator(name = "importRunSummarySeq", sequenceName = "SEQ_IMPORT_RUN_SUMMARY", allocationSize = 50)
    @Column(name = "ID", nullable = false, updatable = false)
    private @Nullable Long id;

    @Column(name = "RUN_ID", nullable = false, updatable = false)
    private long runId;

    @Column(name = "METRIC", nullable = false, updatable = false, length = 64)
    private String metric = "";

    @Column(name = "DIMENSION", updatable = false, length = 64)
    private @Nullable String dimension;

    @Column(name = "METRIC_VALUE", nullable = false, updatable = false)
    private long value;

    /** For JPA. */
    protected ImportRunSummary() {}

    /**
     * One figure.
     *
     * @param runId the run
     * @param metric what is counted
     * @param dimension what it is broken down by, or {@code null} for a total
     * @param value the number
     */
    ImportRunSummary(long runId, String metric, @Nullable String dimension, long value) {
        this.runId = runId;
        this.metric = metric;
        this.dimension = dimension;
        this.value = value;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof ImportRunSummary that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return ImportRunSummary.class.hashCode();
    }
}
