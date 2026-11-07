// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseNumber;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The {@code CASE_SLA} side table as the {@code sla} module's evaluator sees it (BUILD_PLAN §6, ADR-0007): which cases
 * are due a transition, and the transitions themselves. It never writes or locks the case row (it only reads the
 * number, title, owner and team its alerts need), so nobody editing a case ever meets a conflict caused by the
 * evaluator. No {@code CaseScope}: the evaluator is the system, not a person.
 */
public interface CaseSlaService {

    /**
     * One state change of a case's SLA, with what its alerts need (never the description or the subject).
     *
     * @param caseId the case
     * @param from the state before
     * @param to the state after: {@code AT_RISK} or {@code OVERDUE}
     * @param cycle the SLA cycle it happened in
     * @param dueAt when the case is due in that cycle
     * @param about the case's number, title, owner, team and escalation rule
     */
    record SlaTransition(long caseId, SlaState from, SlaState to, int cycle, Instant dueAt, SlaCase about) {}

    /**
     * A case as its SLA alerts address it.
     *
     * @param number the case number
     * @param title the title
     * @param ownerId the owner
     * @param teamId the owning team
     * @param escalateTo who hears of an overdue case, from the case type's SLA policy
     * @param cycle the case's current SLA cycle
     */
    record SlaCase(CaseNumber number, String title, long ownerId, long teamId, EscalateTo escalateTo, int cycle) {}

    /**
     * Cases as their alerts and reminders address them, for jobs (no {@code CaseScope}: the job is the system).
     *
     * @param caseIds the cases
     * @return each case by id; unknown ids are absent
     */
    Map<Long, SlaCase> about(Collection<Long> caseIds);

    /**
     * An overdue case, for the reminders tick's repeats (BUILD_PLAN §6).
     *
     * @param caseId the case
     * @param dueAt when it was due in its current cycle
     * @param reminderDays how often it is reminded once overdue ({@code SLA_POLICY.OVERDUE_REMINDER_DAYS})
     */
    record OverdueCase(long caseId, Instant dueAt, int reminderDays) {}

    /**
     * The overdue cases with ids after {@code afterId}, in id order, at most {@code limit}: one page of the repeats.
     *
     * @param afterId the last id of the previous page, or 0
     * @param limit the page size
     * @return the cases
     */
    List<OverdueCase> overdue(long afterId, int limit);

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
