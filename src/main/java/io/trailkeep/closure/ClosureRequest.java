// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.closure;

import io.trailkeep.user.ImportPeopleService;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.Length;
import org.jspecify.annotations.Nullable;

/**
 * One closure request ({@code CLOSURE_REQUEST}, BUILD_PLAN §4.3 "Request Closure", §5): the outcome (a text snapshot
 * of one of the case type's outcomes, ADR-0011), the resolution summary, optional follow-up actions and the one
 * approver. The decision is WP-19 part b's. The summary and follow-ups are possible PHI: never logged or mailed (§9).
 */
@Entity
@Table(name = "CLOSURE_REQUEST")
class ClosureRequest {

    /** The longest summary or follow-up text, in characters (line breaks count as one). */
    static final int MAX_TEXT = 5000;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "closureRequestSeq")
    @SequenceGenerator(name = "closureRequestSeq", sequenceName = "SEQ_CLOSURE_REQUEST", allocationSize = 50)
    @Column(name = "ID", nullable = false, updatable = false)
    private @Nullable Long id;

    @Column(name = "CASE_ID", nullable = false, updatable = false)
    private long caseId;

    @Column(name = "REQUESTED_BY", nullable = false, updatable = false)
    private long requestedBy;

    @Column(name = "APPROVER_ID", nullable = false)
    private long approverId;

    @Column(name = "REQUESTED_AT", nullable = false, updatable = false)
    private Instant requestedAt = Instant.EPOCH;

    @Column(name = "OUTCOME", nullable = false, updatable = false, length = 40)
    private String outcome = "";

    @Column(name = "RESOLUTION_SUMMARY", nullable = false, updatable = false, length = Length.LONG32)
    private String resolutionSummary = "";

    @Column(name = "FOLLOW_UP_ACTIONS", updatable = false, length = Length.LONG32)
    private @Nullable String followUpActions;

    @Enumerated(EnumType.STRING)
    @Column(name = "DECISION", nullable = false, length = 20)
    private ClosureDecision decision = ClosureDecision.PENDING;

    @Column(name = "DECIDED_BY")
    private @Nullable Long decidedBy;

    @Column(name = "DECIDED_AT")
    private @Nullable Instant decidedAt;

    @Column(name = "REASON", length = 1000)
    private @Nullable String reason;

    /** An imported closure (WP-26, V15): approved in the legacy tracker, recorded here, never decided in Trailkeep. */
    @Column(name = "IMPORTED", nullable = false, updatable = false)
    private boolean imported;

    @Version
    @Column(name = "VERSION", nullable = false)
    private int version;

    /** For JPA. */
    protected ClosureRequest() {}

    /**
     * A new pending request.
     *
     * @param caseId the case
     * @param requestedBy who asks
     * @param approverId who decides
     * @param outcome the outcome, as the case type lists it now
     * @param resolutionSummary the summary
     * @param followUpActions the follow-up actions, or {@code null}
     * @param now when
     */
    ClosureRequest(
            long caseId,
            long requestedBy,
            long approverId,
            String outcome,
            String resolutionSummary,
            @Nullable String followUpActions,
            Instant now) {
        this.caseId = caseId;
        this.requestedBy = requestedBy;
        this.approverId = approverId;
        this.outcome = outcome;
        this.resolutionSummary = resolutionSummary;
        this.followUpActions = followUpActions;
        this.requestedAt = now;
    }

    /**
     * An imported closure (WP-26 part h, design §7.1, R6 8): already approved, requested by the import principal and
     * decided by the legacy-approval principal at the legacy closing time.
     *
     * @param caseId the imported case
     * @param principals the import principal (requester) and the legacy-approval principal (approver and decider)
     * @param outcome the legacy outcome
     * @param resolutionSummary the legacy summary
     * @param closedAt when the legacy case was closed
     * @return the request, not yet persisted
     */
    static ClosureRequest imported(
            long caseId,
            ImportPeopleService.ImportPrincipals principals,
            String outcome,
            String resolutionSummary,
            Instant closedAt) {
        ClosureRequest request = new ClosureRequest(
                caseId, principals.importer(), principals.legacyApproval(), outcome, resolutionSummary, null, closedAt);
        request.decision = ClosureDecision.APPROVED;
        request.decidedBy = principals.legacyApproval();
        request.decidedAt = closedAt;
        request.imported = true;
        return request;
    }

    @Nullable
    Long getId() {
        return id;
    }

    boolean isImported() {
        return imported;
    }

    long getCaseId() {
        return caseId;
    }

    long getApproverId() {
        return approverId;
    }

    long getRequestedBy() {
        return requestedBy;
    }

    String getOutcome() {
        return outcome;
    }

    int getVersion() {
        return version;
    }

    Instant getRequestedAt() {
        return requestedAt;
    }

    String getResolutionSummary() {
        return resolutionSummary;
    }

    @Nullable
    String getFollowUpActions() {
        return followUpActions;
    }

    ClosureDecision getDecision() {
        return decision;
    }

    @Nullable
    Long getDecidedBy() {
        return decidedBy;
    }

    @Nullable
    Instant getDecidedAt() {
        return decidedAt;
    }

    @Nullable
    String getReason() {
        return reason;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof ClosureRequest that && id != null && Objects.equals(id, that.id));
    }

    @Override
    public int hashCode() {
        return ClosureRequest.class.hashCode();
    }

    /** Never the summary or the follow-ups (Part B "Logging"). */
    @Override
    public String toString() {
        return "ClosureRequest[id=" + id + ", decision=" + decision + "]";
    }
}
