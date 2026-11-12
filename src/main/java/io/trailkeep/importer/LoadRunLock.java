// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.LoadRefusal.RefusedException;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One load's hold on its run (WP-26 part t, design §8.2). The load claims the run by moving it to {@code LOADING} with
 * a conditional update on its version, and every transaction it writes in checks that version and moves it on, so of
 * two loads only one writes, on any number of nodes. A load that died leaves {@code LOADING} behind; once it has not
 * moved for {@link LoadCommand#LEASE}, the next load takes it over. While one run of a source is loading, no other run
 * of that source may be claimed: the claim locks the source's runs first, so two claims take turns.
 */
final class LoadRunLock {

    private final ImportRunRepository runs;
    private final TransactionTemplate transactions;
    private final InstantSource clock;
    private final long runId;
    private int version;

    LoadRunLock(ImportRunRepository runs, TransactionTemplate transactions, InstantSource clock, long runId) {
        this.runs = runs;
        this.transactions = transactions;
        this.clock = clock;
        this.runId = runId;
    }

    /**
     * Claims the run, unless a live load holds it or another run of its source; a load that loses the race to claim
     * it is refused too.
     *
     * @param sourceId the run's source
     * @param now now, when the lease starts
     * @param claimed what the claim's transaction does besides
     * @throws RefusedException {@code RUN_LOCKED} or {@code SOURCE_LOADING}, before anything is written
     */
    void claim(String sourceId, Instant now, Runnable claimed) {
        ImportRun run;
        try {
            run = transactions.execute(status -> {
                for (ImportRun other : runs.findBySourceIdOrderByIdAsc(sourceId)) {
                    if (other.loadingSince(now.minus(LoadCommand.LEASE))) {
                        throw new RefusedException(
                                runId, other.getId() == runId ? LoadRefusal.RUN_LOCKED : LoadRefusal.SOURCE_LOADING);
                    }
                }
                ImportRun own = load();
                claimed.run();
                own.loadMoved(ImportRun.Status.LOADING, 0, now);
                return runs.save(own);
            });
        } catch (OptimisticLockingFailureException raced) {
            throw new RefusedException(runId, LoadRefusal.RUN_LOCKED);
        }
        version = Objects.requireNonNull(run, "claimed").getVersion();
    }

    /**
     * Does some work in one transaction that also proves this load still holds the run, and moves the run on.
     *
     * @param cases how many more cases the work loads
     * @param work the work
     * @throws RefusedException {@code RUN_LOCKED} once another load has taken the run over; nothing is written
     */
    void write(int cases, Runnable work) {
        transactions.executeWithoutResult(status -> {
            ImportRun run = held();
            work.run();
            run.loadMoved(ImportRun.Status.LOADING, cases, clock.instant());
            runs.save(run);
        });
        version++;
    }

    /**
     * Ends the load, provided it still holds the run.
     *
     * @param failed whether the run ends {@code FAILED} rather than {@code LOADED}
     */
    void end(boolean failed) {
        transactions.executeWithoutResult(status -> {
            ImportRun run = held();
            run.loadMoved(failed ? ImportRun.Status.FAILED : ImportRun.Status.LOADED, 0, clock.instant());
            runs.save(run);
        });
    }

    /** The run, provided this load still holds it: its version is the one this load last wrote. */
    private ImportRun held() {
        ImportRun run = load();
        if (run.getVersion() != version) {
            throw new RefusedException(runId, LoadRefusal.RUN_LOCKED);
        }
        return run;
    }

    private ImportRun load() {
        return runs.findById(runId).orElseThrow(() -> new IllegalStateException("No import run " + runId));
    }
}
