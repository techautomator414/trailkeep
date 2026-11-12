// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.staging.StagingStore;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes a run's staging (design §5): on {@code purge-staging}, and, before every importer command, for every run
 * whose {@code STAGING_EXPIRES_AT} has passed, signed off or not, so an abandoned run does not keep legacy data. Each
 * run's purge is one transaction with the purge time recorded on the run. The run's content area (files) joins this
 * with the file sources (26l).
 */
@Component
@Profile("importer")
class StagingPurger {

    private final StagingStore staging;
    private final ImportRunRepository runs;
    private final TransactionTemplate transactions;

    StagingPurger(StagingStore staging, ImportRunRepository runs, TransactionTemplate transactions) {
        this.staging = staging;
        this.runs = runs;
        this.transactions = transactions;
    }

    /**
     * The TTL: purges every run whose staging has expired and is not purged yet.
     *
     * @param now the instant expiry is judged against
     * @return how many runs were purged
     */
    int purgeExpired(Instant now) {
        int purged = 0;
        for (ImportRun run : runs.findByStagingExpiresAtLessThanEqualAndStagingPurgedAtIsNull(now)) {
            purge(run.getId(), now);
            purged++;
        }
        return purged;
    }

    /**
     * {@code purge-staging}: removes one run's staging now, whatever its expiry.
     *
     * @param runId the run
     * @param now when
     * @throws IllegalStateException if there is no such run
     */
    void purge(long runId, Instant now) {
        transactions.executeWithoutResult(status -> {
            ImportRun run = runs.findById(runId).orElseThrow(() -> new IllegalStateException("No import run " + runId));
            staging.purge(runId);
            run.stagingPurged(now);
            runs.save(run);
        });
    }
}
