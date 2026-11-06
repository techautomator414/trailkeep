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
