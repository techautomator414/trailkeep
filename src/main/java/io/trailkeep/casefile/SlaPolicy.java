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
import org.jspecify.annotations.Nullable;

/** An SLA policy ({@code SLA_POLICY}); case types point at one. Policies are edited, never deleted. */
@Entity
@Table(name = "SLA_POLICY")
class SlaPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "slaPolicySeq")
    @SequenceGenerator(name = "slaPolicySeq", sequenceName = "SEQ_SLA_POLICY", allocationSize = 50)
    @Column(name = "ID", nullable = false)
    private @Nullable Long id;

    @Column(name = "NAME", nullable = false, length = 120)
    private String name = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "SLA_ANCHOR", nullable = false, length = 14)
    private SlaAnchor anchor = SlaAnchor.IDENTIFIED_ON;

    @Column(name = "TARGET_DAYS", nullable = false)
    private int targetDays;

    @Column(name = "AT_RISK_PCT", nullable = false)
    private int atRiskPct;

    @Column(name = "OVERDUE_REMINDER_DAYS", nullable = false)
    private int overdueReminderDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "ESCALATE_TO", nullable = false, length = 18)
    private EscalateTo escalateTo = EscalateTo.OWNER_AND_MANAGERS;

    /** For JPA. */
    protected SlaPolicy() {}

    SlaPolicy(SlaPolicyCommand command) {
        apply(command);
    }

    final void apply(SlaPolicyCommand command) {
        this.name = command.name().strip();
        this.anchor = command.anchor();
        this.targetDays = command.targetDays();
        this.atRiskPct = command.atRiskPct();
        this.overdueReminderDays = command.overdueReminderDays();
        this.escalateTo = command.escalateTo();
    }

    @Nullable
    Long getId() {
        return id;
    }

    String getName() {
        return name;
    }

    SlaPolicyCommand toCommand() {
        return new SlaPolicyCommand(name, anchor, targetDays, atRiskPct, overdueReminderDays, escalateTo);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof SlaPolicy that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return SlaPolicy.class.hashCode();
    }
}
