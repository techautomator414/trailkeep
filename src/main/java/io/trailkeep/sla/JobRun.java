// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A scheduled job's last success on any replica ({@code JOB_RUN}, BUILD_PLAN §6): the cluster truth the health
 * indicator and the System screen read, so the replicas that did not win the lock are never reported for it.
 */
@Entity
@Table(name = "JOB_RUN")
class JobRun {

    @Id
    @Column(name = "JOB_NAME", nullable = false, length = 64)
    private String jobName = "";

    @Column(name = "LAST_SUCCESS_AT", nullable = false)
    private Instant lastSuccessAt = Instant.EPOCH;

    @Column(name = "LAST_ITEMS", nullable = false)
    private int lastItems;

    /** For JPA. */
    protected JobRun() {}

    /**
     * A job's first recorded success.
     *
     * @param jobName the job
     */
    JobRun(String jobName) {
        this.jobName = jobName;
    }

    /**
     * Records a success.
     *
     * @param at when the run finished
     * @param items how many items it handled
     */
    void succeeded(Instant at, int items) {
        this.lastSuccessAt = at;
        this.lastItems = items;
    }

    Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    int getLastItems() {
        return lastItems;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof JobRun that && jobName.equals(that.jobName));
    }

    @Override
    public int hashCode() {
        return jobName.hashCode();
    }
}
