// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link LoadRunLock#keepAlive} (WP-26 part u): between attachment scans, which run outside every transaction, the load
 * moves its run on once a tenth of the lease has passed since it last did, and not more often.
 */
class LoadRunLockTest {

    /** Transactions that do nothing: the repository is a mock. */
    private static final class NoTransactions extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // Nothing to begin.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // Nothing to commit.
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // Nothing to roll back.
        }
    }

    @Test
    void keepAliveMovesTheRunOnlyOnceItHasNotMovedForOneMinute() {
        Instant start = Instant.parse("2026-01-05T10:00:00Z");
        AtomicReference<Instant> now = new AtomicReference<>(start);
        ImportRunRepository runs = mock(ImportRunRepository.class);
        ImportRun run = mock(ImportRun.class);
        when(runs.findById(7L)).thenReturn(Optional.of(run));
        when(run.getVersion()).thenReturn(0, 1, 2);
        LoadRunLock lock = new LoadRunLock(runs, new TransactionTemplate(new NoTransactions()), now::get, 7L);

        lock.write(0, () -> {});
        now.set(start.plus(Duration.ofSeconds(59)));
        lock.keepAlive();
        verify(runs, times(1)).save(any());

        Instant due = start.plus(LoadCommand.LEASE.dividedBy(10)).plusSeconds(1);
        now.set(due);
        lock.keepAlive();
        verify(runs, times(2)).save(any());
        verify(run).loadMoved(ImportRun.Status.LOADING, 0, due);

        lock.keepAlive();
        verify(runs, times(2)).save(any());
    }
}
