// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.retention;

import io.trailkeep.casefile.CaseRetentionService;
import io.trailkeep.sla.JobRuns;
import jakarta.persistence.PersistenceException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * The hourly archive job (BUILD_PLAN §4.6, WP-24): on one replica at a time, under ShedLock, it archives every closed
 * case whose retention years have passed and that is not on legal hold, in id-ordered chunks of {@link #CHUNK}, one
 * transaction each, so a failing chunk holds up no other and is tried again next hour. Each archived case gets {@code
 * PURGE_AFTER} = now + {@code app.retention.purge-grace}. A run in which every chunk succeeded is recorded in {@code
 * JOB_RUN} for {@link ArchiveHealthIndicator}. It logs counts only.
 */
@Component
class ArchiveJob {

    /** The job's name: its ShedLock row and its {@code JOB_RUN} row. */
    static final String JOB = "archive";

    /** Cases archived per transaction. */
    static final int CHUNK = 500;

    private static final Logger LOG = LoggerFactory.getLogger(ArchiveJob.class);

    private final CaseRetentionService cases;
    private final JobRuns runs;
    private final InstantSource clock;
    private final Duration grace;

    ArchiveJob(
            CaseRetentionService cases,
            JobRuns runs,
            InstantSource clock,
            @Value("${app.retention.purge-grace:P30D}") Duration grace) {
        this.cases = cases;
        this.runs = runs;
        this.clock = clock;
        this.grace = grace;
    }

    @Scheduled(cron = "${app.retention.archive-cron:0 37 * * * *}")
    @SchedulerLock(name = JOB, lockAtMostFor = "PT50M", lockAtLeastFor = "PT1M")
    void tick() {
        run(clock.instant());
    }

    /**
     * One run, as if at {@code now}.
     *
     * @param now the run's instant
     * @return how many cases were archived
     */
    int run(Instant now) {
        List<Long> due = cases.dueForArchive(now);
        int archived = 0;
        int failed = 0;
        for (int from = 0; from < due.size(); from += CHUNK) {
            try {
                archived += cases.archive(due.subList(from, Math.min(from + CHUNK, due.size())), now, grace);
            } catch (PersistenceException | DataAccessException | TransactionException failure) {
                failed++;
                LOG.warn(
                        "Archive chunk failed ({}); it is tried again next hour",
                        failure.getClass().getSimpleName());
            }
        }
        if (failed == 0) {
            runs.succeeded(JOB, clock.instant(), archived);
        }
        LOG.info("Archive: {} cases archived, {} chunks failed", archived, failed);
        return archived;
    }
}
