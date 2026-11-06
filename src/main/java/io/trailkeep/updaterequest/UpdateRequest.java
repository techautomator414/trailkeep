// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.updaterequest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.Length;
import org.jspecify.annotations.Nullable;

/**
 * One request for an update on a case ({@code UPDATE_REQUEST}, BUILD_PLAN §4.3 "Request Update", §5). It is inserted
 * open and answered once, by a conditional bulk update in {@link DefaultUpdateRequestService} that only an open row
 * matches, so a comment and a "Mark answered" racing each other answer it exactly once; it has no {@code @Version}
 * because nothing else about it changes. The message is possible PHI: never logged, never mailed (§9).
 */
@Entity
@Table(name = "UPDATE_REQUEST")
class UpdateRequest {

    /** The longest message, in characters (line breaks count as one). */
    static final int MAX_MESSAGE = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "updateRequestSeq")
    @SequenceGenerator(name = "updateRequestSeq", sequenceName = "SEQ_UPDATE_REQUEST", allocationSize = 50)
    @Column(name = "ID", nullable = false, updatable = false)
    private @Nullable Long id;

    @Column(name = "CASE_ID", nullable = false, updatable = false)
    private long caseId;

    @Column(name = "REQUESTED_BY", nullable = false, updatable = false)
    private long requestedBy;

    @Column(name = "REQUESTED_FROM", nullable = false, updatable = false)
    private long requestedFrom;

    @Column(name = "MESSAGE", nullable = false, updatable = false, length = Length.LONG32)
    private String message = "";

    @Column(name = "REQUESTED_AT", nullable = false, updatable = false)
    private Instant requestedAt = Instant.EPOCH;

    @Column(name = "LAST_REMINDED_AT")
    private @Nullable Instant lastRemindedAt;

    @Column(name = "ANSWERED_AT")
    private @Nullable Instant answeredAt;

    @Column(name = "ANSWERED_BY")
    private @Nullable Long answeredBy;

    @Column(name = "ANSWERED_BY_COMMENT_ID")
    private @Nullable Long answeredByCommentId;

    @Column(name = "OPEN_KEY", length = 41)
    private @Nullable String openKey;

    /** For JPA. */
    protected UpdateRequest() {}

    /**
     * A new open request.
     *
     * @param caseId the case
     * @param requestedBy who asks
     * @param requestedFrom who is asked
     * @param message what is asked
     * @param now when
     */
    UpdateRequest(long caseId, long requestedBy, long requestedFrom, String message, Instant now) {
        this.caseId = caseId;
        this.requestedBy = requestedBy;
        this.requestedFrom = requestedFrom;
        this.message = message;
        this.requestedAt = now;
        // One open request per case and recipient (the column's unique key); cleared when answered.
        this.openKey = caseId + ":" + requestedFrom;
    }

    @Nullable
    Long getId() {
        return id;
    }

    long getRequestedBy() {
        return requestedBy;
    }

    long getRequestedFrom() {
        return requestedFrom;
    }

    String getMessage() {
        return message;
    }

    Instant getRequestedAt() {
        return requestedAt;
    }

    long getCaseId() {
        return caseId;
    }

    boolean isOpen() {
        return answeredAt == null;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof UpdateRequest that && id != null && Objects.equals(id, that.id));
    }

    @Override
    public int hashCode() {
        return UpdateRequest.class.hashCode();
    }

    /** Never the message (Part B "Logging"). */
    @Override
    public String toString() {
        return "UpdateRequest[id=" + id + ", open=" + isOpen() + "]";
    }
}
