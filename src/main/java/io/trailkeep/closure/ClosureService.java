// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.closure;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseNumber;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
     * What the Request closure form offers.
     *
     * @param outcomes the case type's outcomes, in order
     * @param approvers who may approve, by name ({@link #approvers})
     */
    record ClosureChoices(List<String> outcomes, List<Approver> approvers) {

        /**
         * Copies the lists, so the record holds nothing mutable.
         *
         * @param outcomes the outcomes
         * @param approvers the approvers
         */
        public ClosureChoices {
            outcomes = List.copyOf(outcomes);
            approvers = List.copyOf(approvers);
        }
    }

    /**
     * A pending closure request, as the case page's banner shows it. Summary and follow-ups are case content, shown to
     * whoever reads the case and never mailed.
     *
     * @param id the request
     * @param outcome the outcome
     * @param requestedBy who asked, by name
     * @param approverId who decides
     * @param approver who decides, by name
     * @param requestedAt when, in {@code app.timezone}, to the minute
     * @param summary the resolution summary
     * @param followUps the follow-up actions, or {@code null}
     */
    record PendingClosure(
            long id,
            String outcome,
            String requestedBy,
            long approverId,
            String approver,
            LocalDateTime requestedAt,
            String summary,
            @Nullable String followUps) {}

    /**
     * The latest decided closure request of a case, as the case page shows it: on a closed case the closure itself
     * (locked), on an open case the reason it was sent back. Case content: shown to whoever reads the case.
     *
     * @param id the request
     * @param decision the decision
     * @param decidedBy who decided, by name
     * @param decidedAt when, in {@code app.timezone}, to the minute
     * @param reason why, or {@code null}
     * @param outcome the outcome requested
     * @param summary the resolution summary
     * @param followUps the follow-up actions, or {@code null}
     * @param requiresChanges sent back (rejected or changes requested) and nobody has commented since: the case
     *     page's "Requires Changes" badge (BUILD_PLAN §4.3)
     */
    record DecidedClosure(
            long id,
            ClosureDecision decision,
            String decidedBy,
            LocalDateTime decidedAt,
            @Nullable String reason,
            String outcome,
            String summary,
            @Nullable String followUps,
            boolean requiresChanges) {}

    /**
     * A request waiting for the actor's decision, for the dashboard's "Pending my approval".
     *
     * @param id the request
     * @param caseNumber the case
     * @param caseTitle the case's title
     * @param requestedBy who asked, by name
     * @param outcome the outcome requested
     */
    record AwaitingApproval(long id, CaseNumber caseNumber, String caseTitle, String requestedBy, String outcome) {}

    /**
     * The case's latest request, if the actor reads the case and it is decided (an open case's latest request may
     * have been sent back; a closed case's was approved).
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @return the decision, or empty
     */
    Optional<DecidedClosure> lastDecision(Actor actor, String caseNumber);

    /**
     * The pending requests the actor is the approver of, on cases they read, oldest first, at most 100.
     *
     * @param actor the actor
     * @return the requests
     */
    List<AwaitingApproval> awaitingMyApproval(Actor actor);

    /**
     * Who a pending request may be reassigned to ({@code REASSIGN_APPROVER}): the A.2 #18 rule for its original
     * requester, minus its current approver, by name. {@code NOT_FOUND}, then {@code FORBIDDEN}.
     *
     * @param actor who reassigns
     * @param caseNumber the case number as it appears in the URL
     * @return the approvers, by name; empty if nothing is pending
     */
    List<Approver> reassignChoices(Actor actor, String caseNumber);

    /**
     * The form's choices for a case the actor may request closure of: {@code NOT_FOUND}, then {@code FORBIDDEN} as
     * for {@link #requestClosure}.
     *
     * @param actor who would request
     * @param caseNumber the case number as it appears in the URL
     * @return the outcomes and approvers
     */
    ClosureChoices choices(Actor actor, String caseNumber);

    /**
     * The case's pending closure request, if it has one and the actor reads the case.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @return the pending request, or empty
     */
    Optional<PendingClosure> pending(Actor actor, String caseNumber);

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
