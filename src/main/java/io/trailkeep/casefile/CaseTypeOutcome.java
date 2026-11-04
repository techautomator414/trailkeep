// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

/**
 * One allowed closure outcome of a case type ({@code CASE_TYPE_OUTCOME}), with its position in the list. An entity
 * rather than an indexed element collection: reordering an indexed list would update outcomes in place and collide
 * with the {@code (CASE_TYPE_ID, OUTCOME)} key, so the list is replaced as a whole instead.
 */
@Entity
@Table(name = "CASE_TYPE_OUTCOME")
class CaseTypeOutcome {

    /** The composite key. */
    @Embeddable
    record Key(
            @Column(name = "CASE_TYPE_ID", nullable = false) Long caseTypeId,

            @Column(name = "OUTCOME", nullable = false, length = 40)
            String outcome) {}

    @EmbeddedId
    private Key id = new Key(0L, "");

    @Column(name = "SORT_ORDER", nullable = false)
    private int sortOrder;

    /** For JPA. */
    protected CaseTypeOutcome() {}

    CaseTypeOutcome(long caseTypeId, String outcome, int sortOrder) {
        this.id = new Key(caseTypeId, outcome);
        this.sortOrder = sortOrder;
    }

    String outcome() {
        return id.outcome();
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof CaseTypeOutcome that && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
