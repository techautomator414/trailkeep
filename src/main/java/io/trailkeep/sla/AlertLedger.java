// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

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
 * One alert a job decided to raise ({@code ALERT_LEDGER}, BUILD_PLAN §6 "Idempotency"). Insert-only: the unique
 * {@code (CASE_ID, ALERT_TYPE, SLA_CYCLE, PERIOD_KEY)} makes a second row for the same alert fail, so an alert is
 * raised once per cycle and period across restarts and replicas.
 */
@Entity
@Immutable
@Table(name = "ALERT_LEDGER")
class AlertLedger {

    /** The period key of an alert raised once per SLA cycle. */
    static final String ONCE_PER_CYCLE = "CYCLE";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "alertLedgerSeq")
    @SequenceGenerator(name = "alertLedgerSeq", sequenceName = "SEQ_ALERT_LEDGER", allocationSize = 50)
    @Column(name = "ID", nullable = false, updatable = false)
    private @Nullable Long id;

    @Column(name = "CASE_ID", nullable = false, updatable = false)
    private long caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ALERT_TYPE", nullable = false, updatable = false, length = 32)
    private AlertType alertType = AlertType.SLA_AT_RISK;

    @Column(name = "SLA_CYCLE", nullable = false, updatable = false)
    private int slaCycle;

    @Column(name = "PERIOD_KEY", nullable = false, updatable = false, length = 32)
    private String periodKey = ONCE_PER_CYCLE;

    @Column(name = "RECORDED_AT", nullable = false, updatable = false)
    private Instant recordedAt = Instant.EPOCH;

    /** For JPA. */
    protected AlertLedger() {}

    /**
     * An alert raised once per cycle.
     *
     * @param caseId the case
     * @param alertType what was raised
     * @param slaCycle the SLA cycle it belongs to
     * @param now when
     */
    AlertLedger(long caseId, AlertType alertType, int slaCycle, Instant now) {
        this.caseId = caseId;
        this.alertType = alertType;
        this.slaCycle = slaCycle;
        this.recordedAt = now;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof AlertLedger that && id != null && Objects.equals(id, that.id));
    }

    @Override
    public int hashCode() {
        return AlertLedger.class.hashCode();
    }

    @Override
    public String toString() {
        return "AlertLedger[id=" + id + ", type=" + alertType + ", cycle=" + slaCycle + "]";
    }
}
