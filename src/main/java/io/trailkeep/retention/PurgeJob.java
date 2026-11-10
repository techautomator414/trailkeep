// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.retention;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The purge (BUILD_PLAN §4.6, WP-24 part c): tombstones every archived case whose {@code PURGE_AFTER} has passed and
 * that is not on legal hold, in id-ordered chunks of {@link #CHUNK} with one transaction each ({@link PurgeJdbcDao}),
 * then deletes the report runs older than {@code app.retention.report-runs} and the mail naming them. It runs once per
 * start of the {@code purge} profile ({@link PurgeRunner}), never in the web application, and records a fully
 * successful run in {@code JOB_RUN}. It logs counts only: no case number, title or id.
 */
@Component
class PurgeJob {

    /** Cases purged per transaction. */
    static final int CHUNK = 100;

    /** The job's name: its {@code JOB_RUN} row. */
    static final String JOB = "purge";

    private static final Logger LOG = LoggerFactory.getLogger(PurgeJob.class);

    private final PurgeJdbcDao purge;
    private final InstantSource clock;
    private final Duration reportRuns;

    PurgeJob(
            PurgeJdbcDao purge, InstantSource clock, @Value("${app.retention.report-runs:P400D}") Duration reportRuns) {
        this.purge = purge;
        this.clock = clock;
        this.reportRuns = reportRuns;
    }

    /**
     * One run, as if at {@code now}.
     *
     * @param now the run's instant
     * @return how many rows each step removed or changed, summed over the chunks; {@code cases} counts the tombstones
     *     and {@code reportRuns} the report runs deleted
     */
    Map<String, Integer> run(Instant now) {
        List<Long> due = purge.due(now);
        Map<String, Integer> totals = new TreeMap<>();
        for (int from = 0; from < due.size(); from += CHUNK) {
            purge.purge(due.subList(from, Math.min(from + CHUNK, due.size())), now)
                    .forEach((name, count) -> totals.merge(name, count, Integer::sum));
        }
        totals.put("reportRuns", purge.trimReportRuns(now.minus(reportRuns)));
        purge.recordRun(clock.instant(), totals.getOrDefault("cases", 0));
        LOG.info("Purge: {}", totals);
        return totals;
    }
}
