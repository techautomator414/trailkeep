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

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof CaseSla that && caseId != 0 && caseId == that.caseId);
    }

    @Override
    public int hashCode() {
        return CaseSla.class.hashCode();
    }
}
