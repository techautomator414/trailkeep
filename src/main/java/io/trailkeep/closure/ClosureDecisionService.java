// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.closure;

import io.trailkeep.casefile.CaseService.Actor;
import org.jspecify.annotations.Nullable;

/**
 * Deciding a closure request and reassigning its approver (BUILD_PLAN §4.3, §5.2, §6); reopening follows. Each
 * runs under the case's row lock ({@code CaseStatusService.move}) and re-checks, on what the lock holds, that the
 * request is still pending at the version it was read with, so two decisions racing each other yield exactly one.
 * Refusals are {@code DomainException}s: 404 for an unreadable case or a request not on it, 403 if the matrix
 * denies the action or the actor is not the request's approver, 409 when the case or the request moved on, 422 for
 * a missing or over-long reason or an approver the rules do not allow.
 */
public interface ClosureDecisionService {

    /** The longest decision reason, in characters ({@code CLOSURE_REQUEST.REASON}). */
    int MAX_REASON = 1000;

    /**
     * Decides a pending request, as its approver ({@code DECIDE_CLOSURE}): {@link ClosureDecision#APPROVED} closes the
     * case ({@code CLOSED_AT} stamped, the SLA finalised); {@link ClosureDecision#REJECTED} and
     * {@link ClosureDecision#CHANGES_REQUESTED} need a reason and send the case back to {@code OPEN}. Audited as
     * {@code CLOSURE_APPROVED}, {@code CLOSURE_REJECTED} or {@code CLOSURE_CHANGES_REQUESTED} with the reason
     * redacted; the requester and the case's owner (once each, never the approver) get the {@code closure-decided}
     * mail and alert, never the reason.
     *
     * @param actor the approver
     * @param caseNumber the case number as it appears in the URL
     * @param requestId the request; anything unreadable is {@code -1}
     * @param decision the decision, not {@code PENDING}
     * @param reason why, 1 to 1000 characters, required unless approving
     */
    void decide(Actor actor, String caseNumber, long requestId, ClosureDecision decision, @Nullable String reason);

    /**
     * Gives a pending request another approver ({@code REASSIGN_APPROVER}), chosen by the same rule as at request
     * time (the team's Managers minus the requester and the owner, else Compliance Admins), audited
     * {@code APPROVER_REASSIGNED}; the new approver gets the {@code closure-requested} mail and alert.
     *
     * @param actor who reassigns
     * @param caseNumber the case number as it appears in the URL
     * @param requestId the request
     * @param approverId the new approver, not the current one
     */
    void reassignApprover(Actor actor, String caseNumber, long requestId, long approverId);
}
