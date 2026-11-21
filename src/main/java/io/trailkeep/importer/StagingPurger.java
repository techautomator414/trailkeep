// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.staging.StagingStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes a run's staging (design §5): on {@code purge-staging}, and, on the scheduled {@code expire-staging} and
 * before every other importer command, for every run whose {@code STAGING_EXPIRES_AT} has passed, signed off or not, so
 * an abandoned run does not keep legacy data. Each run's purge is one transaction that locks the run, with the purge
 * time recorded on it, and takes the run's content area with it (26n2), before the purge is recorded; a run another
 * purge finished while this one waited for the lock is left alone.
 */
@Component
@Profile("importer")
class StagingPurger {

    private final StagingStore staging;
    private final ImportRunRepository runs;
    private final TransactionTemplate transactions;
    private final ImportProperties properties;

    StagingPurger(
            StagingStore staging,
            ImportRunRepository runs,
            TransactionTemplate transactions,
            ImportProperties properties) {
        this.staging = staging;
        this.runs = runs;
        this.transactions = transactions;
        this.properties = properties;
    }

    /**
     * The TTL: purges every run whose staging has expired and is not purged yet.
     *
     * @param now the instant expiry is judged against
     * @return the ids of the runs this call purged
     */
    List<Long> purgeExpired(Instant now) {
        List<Long> purged = new ArrayList<>();
        for (ImportRun candidate : runs.findByStagingExpiresAtLessThanEqualAndStagingPurgedAtIsNull(now)) {
            long runId = candidate.getId();
            boolean done = Boolean.TRUE.equals(transactions.execute(status -> {
                ImportRun run = runs.findLockedById(runId).orElseThrow();
                Instant expiresAt = run.getStagingExpiresAt();
                if (expiresAt == null || expiresAt.isAfter(now) || run.getStagingPurgedAt() != null) {
                    return false;
                }
                remove(run, now);
                return true;
            }));
            if (done) {
                purged.add(runId);
            }
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
        transactions.executeWithoutResult(status -> remove(
                runs.findLockedById(runId).orElseThrow(() -> new IllegalStateException("No import run " + runId)),
                now));
    }

    private void remove(ImportRun run, Instant now) {
        long runId = run.getId();
        staging.purge(runId);
        ContentArea.delete(properties.contentArea(), runId);
        run.stagingPurged(now);
        runs.save(run);
    }
}
