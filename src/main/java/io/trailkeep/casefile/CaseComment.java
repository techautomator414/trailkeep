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
import java.time.Instant;
import org.hibernate.Length;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * A comment on a case ({@code CASE_COMMENT}). Insert-only: {@code @Immutable}, every column {@code updatable =
 * false}, and the runtime user holds no UPDATE or DELETE grant (BUILD_PLAN §8 rule 7); a correction is a new comment.
 * The author's display name is copied in at write time, so the trail does not change when a person is renamed and
 * imported comments, which have no author id, still name their author.
 */
@Entity
@Immutable
@Table(name = "CASE_COMMENT")
class CaseComment {

    /**
     * The body cap, in characters (IMPLEMENTATION_PLAN A.1 #7). Enforced here and by the service, not by a CHECK:
     * a {@code LENGTH} CHECK on a {@code CLOB} breaks every insert on Oracle XE 21 (see {@code V4_1__comment}).
     */
    static final int MAX_BODY = 20_000;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "caseCommentSeq")
    @SequenceGenerator(name = "caseCommentSeq", sequenceName = "SEQ_CASE_COMMENT", allocationSize = 50)
    @Column(name = "ID", nullable = false, updatable = false)
    private @Nullable Long id;

    @Column(name = "CASE_ID", nullable = false, updatable = false)
    private long caseId;

    /** Markdown as typed. Possible PHI, like a description: never logged, audited only as an HMAC. */
    @Column(name = "BODY", nullable = false, updatable = false, length = Length.LONG32)
    private String body = "";

    @Column(name = "AUTHOR_ID", updatable = false)
    private @Nullable Long authorId;

    @Column(name = "AUTHOR_NAME_SNAPSHOT", nullable = false, updatable = false, length = 200)
    private String authorName = "";

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt = Instant.EPOCH;

    /** For JPA. */
    protected CaseComment() {}

    /**
     * A new comment.
     *
     * @param caseId the case
     * @param body the Markdown: not blank and at most {@link #MAX_BODY} characters
     * @param authorId who wrote it; {@code null} only for an imported comment
     * @param authorName the author's display name now
     * @param now when it was written
     * @return the comment, not yet persisted
     * @throws IllegalArgumentException if the body is blank or too long
     */
    static CaseComment of(long caseId, String body, @Nullable Long authorId, String authorName, Instant now) {
        if (body.isBlank() || body.length() > MAX_BODY) {
            throw new IllegalArgumentException("a comment body is 1 to " + MAX_BODY + " characters");
        }
        return new CaseComment(caseId, body, authorId, authorName, now);
    }

    private CaseComment(long caseId, String body, @Nullable Long authorId, String authorName, Instant now) {
        this.caseId = caseId;
        this.body = body;
        this.authorId = authorId;
        this.authorName = authorName;
        this.createdAt = now;
    }

    @Nullable
    Long getId() {
        return id;
    }

    long getCaseId() {
        return caseId;
    }

    String getBody() {
        return body;
    }

    @Nullable
    Long getAuthorId() {
        return authorId;
    }

    String getAuthorName() {
        return authorName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof CaseComment that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return CaseComment.class.hashCode();
    }

    @Override
    public String toString() {
        return "CaseComment[id=" + id + ", caseId=" + caseId + "]";
    }
}
