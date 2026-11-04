// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

/**
 * A case type ({@code CASE_TYPE}). Holds its SLA policy's id rather than an association: the policy is read by id
 * where needed, never navigated. Its outcomes are {@link CaseTypeOutcome} rows. Deactivated, never deleted.
 */
@Entity
@Table(name = "CASE_TYPE")
class CaseType {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "caseTypeSeq")
    @SequenceGenerator(name = "caseTypeSeq", sequenceName = "SEQ_CASE_TYPE", allocationSize = 50)
    @Column(name = "ID", nullable = false)
    private @Nullable Long id;

    @Column(name = "NAME", nullable = false, length = 120)
    private String name = "";

    @Column(name = "SLA_POLICY_ID", nullable = false)
    private long slaPolicyId;

    @Column(name = "RETENTION_YEARS", nullable = false)
    private int retentionYears;

    @Column(name = "ACTIVE", nullable = false)
    private boolean active = true;

    /** For JPA. */
    protected CaseType() {}

    CaseType(CaseTypeCommand command) {
        apply(command);
    }

    final void apply(CaseTypeCommand command) {
        this.name = command.name().strip();
        this.slaPolicyId = command.slaPolicyId();
        this.retentionYears = command.retentionYears();
        this.active = command.active();
    }

    @Nullable
    Long getId() {
        return id;
    }

    String getName() {
        return name;
    }

    long getSlaPolicyId() {
        return slaPolicyId;
    }

    int getRetentionYears() {
        return retentionYears;
    }

    boolean isActive() {
        return active;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof CaseType that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return CaseType.class.hashCode();
    }
}
