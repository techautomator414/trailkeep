// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.closure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * One reopen of a closed case ({@code CASE_REOPEN}, BUILD_PLAN §4.3, §5.2: an event, not a state). Insert-only:
 * {@code @Immutable}, every column {@code updatable = false}. The reason is possible PHI: never logged or mailed (§9).
 */
@Entity
@Immutable
@Table(name = "CASE_REOPEN")
class CaseReopen {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "caseReopenSeq")
    @SequenceGenerator(name = "caseReopenSeq", sequenceName = "SEQ_CASE_REOPEN", allocationSize = 50)
    @Column(name = "ID", nullable = false, updatable = false)
    private @Nullable Long id;

    @Column(name = "CASE_ID", nullable = false, updatable = false)
    private long caseId;

    @Column(name = "REOPENED_BY", nullable = false, updatable = false)
    private long reopenedBy;

    @Column(name = "REOPENED_AT", nullable = false, updatable = false)
    private Instant reopenedAt = Instant.EPOCH;

    @Column(name = "REASON", nullable = false, updatable = false, length = 1000)
    private String reason = "";

    @Column(name = "SLA_CYCLE", nullable = false, updatable = false)
    private int slaCycle;

    @Column(name = "CLOSURE_REQUEST_ID", updatable = false)
    private @Nullable Long closureRequestId;

    /** For JPA. */
    protected CaseReopen() {}

    /**
     * A reopen.
     *
     * @param caseId the case
     * @param reopenedBy who reopened it
     * @param reason why
     * @param slaCycle the SLA cycle it started
     * @param closureRequestId the request whose approval closed the case, or {@code null}
     * @param now when
     */
    CaseReopen(
            long caseId, long reopenedBy, String reason, int slaCycle, @Nullable Long closureRequestId, Instant now) {
        this.caseId = caseId;
        this.reopenedBy = reopenedBy;
        this.reason = reason;
        this.slaCycle = slaCycle;
        this.closureRequestId = closureRequestId;
        this.reopenedAt = now;
    }

    @Nullable
    Long getId() {
        return id;
    }

    long getReopenedBy() {
        return reopenedBy;
    }

    Instant getReopenedAt() {
        return reopenedAt;
    }

    String getReason() {
        return reason;
    }

    int getSlaCycle() {
        return slaCycle;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof CaseReopen that && id != null && Objects.equals(id, that.id));
    }

    @Override
    public int hashCode() {
        return CaseReopen.class.hashCode();
    }

    /** Never the reason (Part B "Logging"). */
    @Override
    public String toString() {
        return "CaseReopen[id=" + id + ", cycle=" + slaCycle + "]";
    }
}
