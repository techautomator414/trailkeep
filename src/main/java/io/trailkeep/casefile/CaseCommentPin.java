// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * A pinned comment ({@code CASE_COMMENT_PIN}, BUILD_PLAN §4.3). A comment has at most one pin (§5.1), so the comment's
 * id is the key, assigned rather than drawn from a sequence. A pin is written and deleted, never changed ({@code
 * Immutable}); keeping pins out of {@code CASE_COMMENT} is what lets that table stay insert-only. The pin names its
 * comment's case as well, and a composite foreign key makes a pin on another case's comment impossible.
 */
@Entity
@Immutable
@Table(name = "CASE_COMMENT_PIN")
class CaseCommentPin {

    @Id
    @Column(name = "COMMENT_ID", nullable = false, updatable = false)
    private long commentId;

    @Column(name = "CASE_ID", nullable = false, updatable = false)
    private long caseId;

    @Column(name = "PINNED_BY", nullable = false, updatable = false)
    private long pinnedBy;

    @Column(name = "PINNED_AT", nullable = false, updatable = false)
    private Instant pinnedAt = Instant.EPOCH;

    /** For JPA. */
    protected CaseCommentPin() {}

    /**
     * A new pin.
     *
     * @param commentId the pinned comment
     * @param caseId the comment's case
     * @param pinnedBy who pinned it
     * @param pinnedAt when
     */
    CaseCommentPin(long commentId, long caseId, long pinnedBy, Instant pinnedAt) {
        this.commentId = commentId;
        this.caseId = caseId;
        this.pinnedBy = pinnedBy;
        this.pinnedAt = pinnedAt;
    }

    long getCommentId() {
        return commentId;
    }

    long getCaseId() {
        return caseId;
    }

    long getPinnedBy() {
        return pinnedBy;
    }

    Instant getPinnedAt() {
        return pinnedAt;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof CaseCommentPin that && commentId == that.commentId);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(commentId);
    }

    @Override
    public String toString() {
        return "CaseCommentPin[commentId=" + commentId + ", caseId=" + caseId + "]";
    }
}
