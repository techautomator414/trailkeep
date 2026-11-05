// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.Length;
import org.jspecify.annotations.Nullable;

/**
 * A case's search text ({@code CASE_SEARCH}): title and description, later comments (WP-13); never the subject. A
 * side table without {@code @Version}, so a colleague's comment never fails your save (BUILD_PLAN §8 rule 4).
 */
@Entity
@Table(name = "CASE_SEARCH")
class CaseSearch {

    @Id
    @Column(name = "CASE_ID", nullable = false)
    private long caseId;

    @Column(name = "SEARCH_TEXT", nullable = false, length = Length.LONG32)
    private String text = "";

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt = Instant.EPOCH;

    /** For JPA. */
    protected CaseSearch() {}

    /**
     * The search row of a new case.
     *
     * @param caseId the case
     * @param text the initial text
     * @param now when it was written
     */
    CaseSearch(long caseId, String text, Instant now) {
        this.caseId = caseId;
        this.text = text;
        this.updatedAt = now;
    }

    /**
     * Replaces the text after a title or description edit; the caller holds the row lock (BUILD_PLAN §8 rule 4).
     *
     * @param text the rebuilt text
     * @param now when it was written
     */
    void rebuild(String text, Instant now) {
        this.text = text;
        this.updatedAt = now;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof CaseSearch that && caseId != 0 && caseId == that.caseId);
    }

    @Override
    public int hashCode() {
        return CaseSearch.class.hashCode();
    }
}
