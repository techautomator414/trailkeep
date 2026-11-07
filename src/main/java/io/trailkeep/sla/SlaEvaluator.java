// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import io.trailkeep.casefile.CaseSlaService;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The SLA evaluator (BUILD_PLAN §6, ADR-0007): every 15 minutes, on one replica at a time (ShedLock on database time,
 * held at most 14 minutes and at least 1, so a fast run on one replica does not let another start the same minute),
 * it moves on the cases whose at-risk or due time has passed, in id-ordered chunks of 500 with one transaction per
 * chunk, then records its success in {@code JOB_RUN}. It reads and writes {@code CASE_SLA} only, never the case row.
 * Pending closure does not pause the clock.
 */
@Component
class SlaEvaluator {

    /** The job's name: its ShedLock row and its {@code JOB_RUN} row. */
    static final String JOB = "sla-evaluator";

    /** Cases per chunk transaction. */
    static final int CHUNK = 500;

    private static final Logger LOG = LoggerFactory.getLogger(SlaEvaluator.class);

    private final CaseSlaService slas;
    private final SlaChunkWriter writer;
    private final InstantSource clock;

    SlaEvaluator(CaseSlaService slas, SlaChunkWriter writer, InstantSource clock) {
        this.slas = slas;
        this.writer = writer;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.sla.evaluate-cron:0 */15 * * * *}")
    @SchedulerLock(name = JOB, lockAtMostFor = "PT14M", lockAtLeastFor = "PT1M")
    void tick() {
        evaluate(clock.instant());
    }

    /**
     * One evaluation, as if at {@code now}.
     *
     * @param now the evaluation instant
     * @return how many cases changed state
     */
    int evaluate(Instant now) {
        List<Long> candidates = slas.dueForEvaluation(now);
        int moved = 0;
        for (int start = 0; start < candidates.size(); start += CHUNK) {
            moved += writer.write(candidates.subList(start, Math.min(candidates.size(), start + CHUNK)), now);
        }
        writer.succeeded(JOB, clock.instant(), moved);
        LOG.info("SLA evaluation: {} candidates, {} transitions", candidates.size(), moved);
        return moved;
    }
}
