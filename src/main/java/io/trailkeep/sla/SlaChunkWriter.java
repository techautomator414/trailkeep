// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.casefile.CaseSlaService;
import io.trailkeep.casefile.CaseSlaService.SlaTransition;
import io.trailkeep.casefile.SlaState;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One chunk of an evaluation in one transaction (BUILD_PLAN §6): the transitions, their history events and their
 * ledger rows commit or roll back together, so a crash between chunks loses nothing and repeats nothing.
 */
@Service
@Transactional
class SlaChunkWriter {

    private final CaseSlaService slas;
    private final AuditRecorder audit;
    private final EntityManager entityManager;

    SlaChunkWriter(CaseSlaService slas, AuditRecorder audit, EntityManager entityManager) {
        this.slas = slas;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    /**
     * Moves one chunk of cases on.
     *
     * @param caseIds at most one chunk of candidate ids
     * @param now the evaluation instant
     * @return how many cases changed state
     */
    int write(List<Long> caseIds, Instant now) {
        List<SlaTransition> moved = slas.advance(caseIds, now);
        for (SlaTransition t : moved) {
            audit.record(new AuditEntry(
                    null,
                    null,
                    AuditOrigin.SCHEDULER,
                    t.caseId(),
                    "CASE_SLA",
                    t.caseId(),
                    "SLA_STATE_CHANGED",
                    audit.diff(new State(t.from(), t.cycle()), new State(t.to(), t.cycle()))));
            AlertType alert = t.to() == SlaState.AT_RISK ? AlertType.SLA_AT_RISK : AlertType.SLA_OVERDUE;
            entityManager.persist(new AlertLedger(t.caseId(), alert, t.cycle(), now));
        }
        return moved.size();
    }

    /**
     * Records a successful run in {@code JOB_RUN}.
     *
     * @param job the job's name
     * @param at when the run finished
     * @param items how many items it handled
     */
    void succeeded(String job, Instant at, int items) {
        JobRun run = entityManager.find(JobRun.class, job);
        if (run == null) {
            run = new JobRun(job);
            entityManager.persist(run);
        }
        run.succeeded(at, items);
    }

    /**
     * The audit snapshot of a transition.
     *
     * @param state the SLA state
     * @param cycle the SLA cycle
     */
    record State(SlaState state, int cycle) {}
}
