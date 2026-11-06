// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.closure;

import io.trailkeep.casefile.CaseService.Actor;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Request Closure (BUILD_PLAN §4.3, §5.2, IMPLEMENTATION_PLAN A.2 #18): the case's one approval gate. A request
 * carries an outcome from the case type's list, a resolution summary, optional follow-up actions and one approver, and
 * moves the case from {@code OPEN} to {@code PENDING_CLOSURE}, where it cannot be reassigned. Refusals are
 * {@code DomainException}s: 404 for an unreadable case, 403 without {@code REQUEST_CLOSURE} (so also for a case that
 * is not open), 422 for a bad outcome, text or approver, 409 when a concurrent request got there first.
 */
public interface ClosureService {

    /**
     * Someone who may approve the request.
     *
     * @param id the person's id
     * @param name their display name
     */
    record Approver(long id, String name) {}

    /**
     * Who may approve a closure the actor requests (A.2 #18, separation of duties): the team's enabled Managers minus
     * the requester and the case's owner; if that leaves nobody (the owner is the team's only Manager, say), the
     * enabled Compliance Admins minus the same two. By name.
     *
     * @param actor who would request
     * @param caseNumber the case number as it appears in the URL
     * @return the approvers, by name
     */
    List<Approver> approvers(Actor actor, String caseNumber);

    /**
     * Requests closure: the case moves to {@code PENDING_CLOSURE}, {@code CLOSURE_REQUESTED} is audited with the
     * summary and follow-ups redacted, and the approver gets the {@code closure-requested} mail and alert (number,
     * title, requester, outcome and a link; never the summary).
     *
     * @param actor who requests
     * @param caseNumber the case number as it appears in the URL
     * @param outcome one of the case type's outcomes, kept as a text snapshot
     * @param summary the resolution summary, 1 to 5000 characters
     * @param followUps follow-up actions, up to 5000 characters, or {@code null}
     * @param approverId one of {@link #approvers}
     * @return the request's id
     */
    long requestClosure(
            Actor actor,
            String caseNumber,
            String outcome,
            String summary,
            @Nullable String followUps,
            long approverId);
}
