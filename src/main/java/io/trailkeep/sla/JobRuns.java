// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code JOB_RUN}, each scheduled job's last success on any replica (BUILD_PLAN §6, Part A #30). Public so that a job
 * in another module (the report delivery of WP-23) records its runs the same way.
 */
@Component
@Transactional
public class JobRuns {

    private final EntityManager entityManager;

    JobRuns(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Records a successful run.
     *
     * @param job the job's name
     * @param at when the run finished
     * @param items how many items it handled
     */
    public void succeeded(String job, Instant at, int items) {
        JobRun run = entityManager.find(JobRun.class, job);
        if (run == null) {
            run = new JobRun(job);
            entityManager.persist(run);
        }
        run.succeeded(at, items);
    }

    /**
     * When a job last succeeded.
     *
     * @param job the job's name
     * @return the instant, or empty if it never has
     */
    @Transactional(readOnly = true)
    public Optional<Instant> lastSuccess(String job) {
        return Optional.ofNullable(entityManager.find(JobRun.class, job)).map(JobRun::getLastSuccessAt);
    }
}
