// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One legal hold placed or lifted ({@code LEGAL_HOLD_EVENT}, V8, WP-24 part b2): the readable history of a case's
 * holds, kept by the purge job like the audit trail. Insert-only: {@code @Immutable}, every column {@code updatable =
 * false}. The reason is possible PHI: shown to Compliance Admins only, never logged or mailed.
 */
@Entity
@Immutable
@Table(name = "LEGAL_HOLD_EVENT")
class LegalHoldEvent {

    /** What happened. */
    enum Action {
        PLACED,
        LIFTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "legalHoldEventSeq")
    @SequenceGenerator(name = "legalHoldEventSeq", sequenceName = "SEQ_LEGAL_HOLD_EVENT", allocationSize = 50)
    @Column(name = "ID", nullable = false, updatable = false)
    private @Nullable Long id;

    @Column(name = "CASE_ID", nullable = false, updatable = false)
    private long caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ACTION", nullable = false, updatable = false, length = 8)
    private Action action = Action.PLACED;

    @Column(name = "CHANGED_BY", nullable = false, updatable = false)
    private long changedBy;

    @Column(name = "CHANGED_AT", nullable = false, updatable = false)
    private Instant changedAt = Instant.EPOCH;

    @Column(name = "REASON", nullable = false, updatable = false, length = 500)
    private String reason = "";

    @Column(name = "PURGE_AFTER", updatable = false)
    private @Nullable Instant purgeAfter;

    /** For JPA. */
    protected LegalHoldEvent() {}

    /**
     * A hold placed or lifted.
     *
     * @param caseId the case
     * @param action placed or lifted
     * @param changedBy who
     * @param changedAt when
     * @param reason why
     * @param purgeAfter the case's purge date after the change, or {@code null} while it is not archived
     */
    LegalHoldEvent(
            long caseId,
            Action action,
            long changedBy,
            Instant changedAt,
            String reason,
            @Nullable Instant purgeAfter) {
        this.caseId = caseId;
        this.action = action;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
        this.reason = reason;
        this.purgeAfter = purgeAfter;
    }

    Action getAction() {
        return action;
    }

    long getChangedBy() {
        return changedBy;
    }

    Instant getChangedAt() {
        return changedAt;
    }

    String getReason() {
        return reason;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof LegalHoldEvent that && id != null && Objects.equals(id, that.id));
    }

    @Override
    public int hashCode() {
        return LegalHoldEvent.class.hashCode();
    }

    /** Never the reason (Part B "Logging"). */
    @Override
    public String toString() {
        return "LegalHoldEvent[id=" + id + ", " + action + "]";
    }
}
