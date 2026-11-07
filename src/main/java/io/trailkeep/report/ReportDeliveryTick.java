// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import io.trailkeep.sla.JobRuns;
import java.time.Instant;
import java.time.InstantSource;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The hourly report-delivery job (BUILD_PLAN §6 "Aging report delivery", WP-23): on one replica at a time, under
 * ShedLock, it delivers every enabled schedule whose {@code NEXT_RUN_AT} has come, each in its own transaction
 * ({@link ReportDeliveryWriter#deliver}), so one failing team holds up no other and is tried again next hour. A tick
 * in which every due schedule was delivered is recorded in {@code JOB_RUN} for {@link ReportDeliveryHealthIndicator}.
 */
@Component
class ReportDeliveryTick {

    /** The job's name: its ShedLock row and its {@code JOB_RUN} row. */
    static final String JOB = "report-delivery";

    private static final Logger LOG = LoggerFactory.getLogger(ReportDeliveryTick.class);

    private final ReportDeliveryWriter writer;
    private final JobRuns runs;
    private final InstantSource clock;

    ReportDeliveryTick(ReportDeliveryWriter writer, JobRuns runs, InstantSource clock) {
        this.writer = writer;
        this.runs = runs;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.reports.delivery-cron:0 23 * * * *}")
    @SchedulerLock(name = JOB, lockAtMostFor = "PT50M", lockAtLeastFor = "PT1M")
    void tick() {
        run(clock.instant());
    }

    /**
     * One tick, as if at {@code now}.
     *
     * @param now the tick's instant
     * @return how many schedules were delivered
     */
    int run(Instant now) {
        int delivered = 0;
        int failed = 0;
        for (long schedule : writer.due(now)) {
            try {
                delivered += writer.deliver(schedule, now) ? 1 : 0;
            } catch (DataAccessException | IllegalStateException failure) {
                failed++;
                // Only the exception's class: its message may quote a case title (Part B "Logging").
                LOG.warn(
                        "Report delivery failed for schedule {} ({}); it is tried again next hour",
                        schedule,
                        failure.getClass().getSimpleName());
            }
        }
        if (failed == 0) {
            runs.succeeded(JOB, clock.instant(), delivered);
        }
        LOG.info("Report delivery: {} schedules delivered, {} failed", delivered, failed);
        return delivered;
    }
}
