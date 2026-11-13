// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A case's SLA state ({@code CASE_SLA}), one row per case. A side table without {@code @Version}: initialised here on
 * create, recomputed by the {@code sla} evaluator, finalised by {@code closure}, so none of them conflicts with a
 * person editing the case (BUILD_PLAN §6).
 */
@Entity
@Table(name = "CASE_SLA")
class CaseSla {

    @Id
    @Column(name = "CASE_ID", nullable = false)
    private long caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "SLA_STATE", nullable = false, length = 16)
    private SlaState state = SlaState.ON_TRACK;

    @Column(name = "SLA_DUE_AT")
    private @Nullable Instant dueAt;

    @Column(name = "AT_RISK_AT")
    private @Nullable Instant atRiskAt;

    @Column(name = "SLA_CYCLE", nullable = false)
    private int cycle = 1;

    @Column(name = "LAST_EVALUATED_AT")
    private @Nullable Instant lastEvaluatedAt;

    /** For JPA. */
    protected CaseSla() {}

    /**
     * The first cycle of a new case, on track.
     *
     * @param caseId the case
     * @param atRiskAt when it turns at risk
     * @param dueAt when it is due
     */
    CaseSla(long caseId, Instant atRiskAt, Instant dueAt) {
        this.caseId = caseId;
        this.atRiskAt = atRiskAt;
        this.dueAt = dueAt;
    }

    /**
     * The row of an imported closed case (design §7.1): no SLA applies, so no due or at-risk time.
     *
     * @param caseId the case
     * @return the row
     */
    static CaseSla notApplicable(long caseId) {
        CaseSla sla = new CaseSla();
        sla.caseId = caseId;
        sla.state = SlaState.NOT_APPLICABLE;
        return sla;
    }

    /**
     * The row of an imported open case (design §7.1): the clock from its original dates, in the state it is in at
     * load, so the first aging report is honest.
     *
     * @param caseId the case
     * @param clock the at-risk and due instants from the one anchor rule
     * @param now the load instant
     * @return the row
     */
    static CaseSla importedOpen(long caseId, SlaClock clock, Instant now) {
        CaseSla sla = new CaseSla(caseId, clock.atRiskAt(), clock.dueAt());
        for (SlaState next = sla.next(now); next != null; next = sla.next(now)) {
            sla.state = next;
        }
        return sla;
    }

    /**
     * Finalises the state at closure (BUILD_PLAN §6 "Closure"): {@code MET} on or before the due time,
     * {@code BREACHED} after it; a case without an SLA keeps {@code NOT_APPLICABLE}.
     *
     * @param closedAt when the case closed
     */
    void finish(Instant closedAt) {
        Instant due = dueAt;
        if (state != SlaState.NOT_APPLICABLE && due != null) {
            state = closedAt.isAfter(due) ? SlaState.BREACHED : SlaState.MET;
        }
    }

    int getCycle() {
        return cycle;
    }

    long getCaseId() {
        return caseId;
    }

    SlaState getState() {
        return state;
    }

    /**
     * The state the evaluator moves this row to at {@code now}, or {@code null} to leave it: past the due time an open
     * clock is {@code OVERDUE}, past the at-risk time an on-track one is {@code AT_RISK} (BUILD_PLAN §6).
     *
     * @param now the evaluation instant
     * @return the next state, or {@code null}
     */
    @Nullable
    SlaState next(Instant now) {
        Instant due = dueAt;
        Instant atRisk = atRiskAt;
        if (due == null || atRisk == null || (state != SlaState.ON_TRACK && state != SlaState.AT_RISK)) {
            return null;
        }
        if (due.isAfter(now)) {
            return state == SlaState.ON_TRACK && !atRisk.isAfter(now) ? SlaState.AT_RISK : null;
        }
        return SlaState.OVERDUE;
    }

    Instant getDueAt() {
        return Objects.requireNonNull(dueAt, "an SLA in a clock state has a due time");
    }

    /**
     * This open case's dry-run forecast (WP-26 part ac): its state, and whether, overdue, its first overdue repeat is
     * due at {@code at} by the reminders tick's rule ({@link CaseSlaService.OverdueCase#repeatPeriod}).
     *
     * @param reminderDays its policy's {@code OVERDUE_REMINDER_DAYS}
     * @param at the forecast instant
     * @return the forecast
     */
    CaseImportService.SlaForecast.Forecast forecast(int reminderDays, Instant at) {
        boolean repeatDue = state == SlaState.OVERDUE
                && new CaseSlaService.OverdueCase(caseId, getDueAt(), reminderDays).repeatPeriod(at) != null;
        return new CaseImportService.SlaForecast.Forecast(state, repeatDue);
    }

    /**
     * Starts the next cycle at a reopen (ADR-0007): the cycle increments, so "at risk once" is once per cycle, and
     * the clock runs from the reopen instant.
     *
     * @param clock the new cycle's at-risk and due instants
     */
    void restart(SlaClock clock) {
        cycle++;
        state = SlaState.ON_TRACK;
        atRiskAt = clock.atRiskAt();
        dueAt = clock.dueAt();
        lastEvaluatedAt = null;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof CaseSla that && caseId != 0 && caseId == that.caseId);
    }

    @Override
    public int hashCode() {
        return CaseSla.class.hashCode();
    }
}
