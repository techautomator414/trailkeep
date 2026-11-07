// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * The {@code CASE_SLA} side table as the {@code sla} module's evaluator sees it (BUILD_PLAN §6, ADR-0007): which cases
 * are due a transition, and the transitions themselves. Neither reads nor writes the case row, so nobody editing a
 * case ever meets a conflict caused by the evaluator. No {@code CaseScope}: the evaluator is the system, not a person.
 */
public interface CaseSlaService {

    /**
     * One state change of a case's SLA.
     *
     * @param caseId the case
     * @param from the state before
     * @param to the state after: {@code AT_RISK} or {@code OVERDUE}
     * @param cycle the SLA cycle it happened in
     * @param dueAt when the case is due in that cycle
     */
    record SlaTransition(long caseId, SlaState from, SlaState to, int cycle, Instant dueAt) {}

    /**
     * The cases whose SLA should move on at {@code now}, by id: on track and past their at-risk time, or at risk and
     * past their due time, through the indexes {@code (SLA_STATE, AT_RISK_AT)} and {@code (SLA_STATE, SLA_DUE_AT)}.
     *
     * @param now the evaluation instant
     * @return the case ids, ascending
     */
    List<Long> dueForEvaluation(Instant now);

    /**
     * Moves the given cases on, in the caller's transaction: locks their rows, re-reads what each holds, and writes
     * {@code ON_TRACK → AT_RISK}, {@code ON_TRACK → OVERDUE} (both times passed) or {@code AT_RISK → OVERDUE} with
     * {@code LAST_EVALUATED_AT}, one bulk update per kind of change. A case that moved meanwhile (closed, reopened,
     * already moved) is left alone.
     *
     * @param caseIds the cases, from {@link #dueForEvaluation}
     * @param now the evaluation instant
     * @return the transitions written, by case id
     */
    List<SlaTransition> advance(Collection<Long> caseIds, Instant now);
}
