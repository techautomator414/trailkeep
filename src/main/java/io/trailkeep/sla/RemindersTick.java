// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import io.trailkeep.sla.ReminderWriter.Page;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The reminders tick (BUILD_PLAN §6, IMPLEMENTATION_PLAN WP-17b): hourly, on one replica at a time (ShedLock on
 * database time, held at most 50 minutes and at least 1), it reminds the recipients of update requests open for
 * {@code app.reminders.update-request-days} (default 3) since they were asked or last reminded, and the approvers of
 * closure requests pending for {@code app.reminders.closure-days} (default 3) since they were made or last reminded.
 * Pages of 500 in id order, one transaction each; the run's success goes to {@code JOB_RUN}.
 */
@Component
class RemindersTick {

    /** The job's name: its ShedLock row and its {@code JOB_RUN} row. */
    static final String JOB = "reminders-tick";

    private static final Logger LOG = LoggerFactory.getLogger(RemindersTick.class);

    private final ReminderWriter writer;
    private final JobRuns runs;
    private final InstantSource clock;
    private final Duration updateRequestAge;
    private final Duration closureAge;

    RemindersTick(
            ReminderWriter writer,
            JobRuns runs,
            InstantSource clock,
            @Value("${app.reminders.update-request-days:3}") int updateRequestDays,
            @Value("${app.reminders.closure-days:3}") int closureDays) {
        this.writer = writer;
        this.runs = runs;
        this.clock = clock;
        this.updateRequestAge = Duration.ofDays(updateRequestDays);
        this.closureAge = Duration.ofDays(closureDays);
    }

    @Scheduled(cron = "${app.reminders.cron:0 7 * * * *}")
    @SchedulerLock(name = JOB, lockAtMostFor = "PT50M", lockAtLeastFor = "PT1M")
    void tick() {
        run(clock.instant());
    }

    /**
     * One tick, as if at {@code now}.
     *
     * @param now the tick's instant
     * @return how many people were reminded
     */
    int run(Instant now) {
        int updates = pages(now.minus(updateRequestAge), now, writer::updateRequests);
        int closures = pages(now.minus(closureAge), now, writer::closureRequests);
        runs.succeeded(JOB, clock.instant(), updates + closures);
        LOG.info("Reminders tick: {} update-request and {} closure reminders", updates, closures);
        return updates + closures;
    }

    private static int pages(Instant cutoff, Instant now, PageWriter writer) {
        int reminded = 0;
        long after = 0;
        Page page;
        do {
            page = writer.write(cutoff, after, now);
            reminded += page.reminded();
            after = page.lastId();
        } while (page.read() == SlaEvaluator.CHUNK);
        return reminded;
    }

    /** One of the writer's passes. */
    @FunctionalInterface
    private interface PageWriter {
        Page write(Instant cutoff, long afterId, Instant now);
    }
}
