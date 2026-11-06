// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.CaseService.CaseRef;
import io.trailkeep.security.CaseAction;
import io.trailkeep.security.CaseFacts;
import io.trailkeep.shared.CaseStatus;
import java.util.List;

/**
 * A case's status, for the {@code closure} module (BUILD_PLAN §5.2): what a closure request needs to know about a
 * case, and the one way another module moves a case from one status to another. Both follow ADR-0005's order (404,
 * then 403) and run in the caller's transaction. Refusals are {@link CaseException}s.
 */
public interface CaseStatusService {

    /**
     * A case on which the actor may request closure, with what the request is checked against.
     *
     * @param ref the case
     * @param facts the facts the permission matrix and the approver rules decide on
     * @param outcomes the case type's outcomes, in their order (ADR-0011)
     */
    record ClosableCase(CaseRef ref, CaseFacts facts, List<String> outcomes) {

        /**
         * Copies the list, so the record holds nothing mutable.
         *
         * @param ref the case
         * @param facts the facts
         * @param outcomes the outcomes
         */
        public ClosableCase {
            outcomes = List.copyOf(outcomes);
        }
    }

    /**
     * The case, if the actor may request its closure: {@code NOT_FOUND}, then {@code FORBIDDEN} without
     * {@code REQUEST_CLOSURE} (which the matrix allows only on an open case).
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @return the case with its facts and outcomes
     */
    ClosableCase closable(Actor actor, String caseNumber);

    /**
     * A case a status move locked, as the lock holds it.
     *
     * @param ref the case
     * @param facts the facts the matrix decided on under the lock
     */
    record MovedCase(CaseRef ref, CaseFacts facts) {}

    /**
     * Moves a case from one status to another under its row lock: {@code NOT_FOUND}, then {@code FORBIDDEN} if the
     * matrix denies {@code action}; then, on the case re-read under the lock, {@code CONFLICT} if it is no longer in
     * {@code from} (a concurrent request or decision got there first) and {@code FORBIDDEN} if the matrix now denies
     * the action. Closing stamps {@code CLOSED_AT} and finalises the SLA ({@code MET} or {@code BREACHED});
     * reopening a closed case starts the next SLA cycle from now. {@code from == to} only takes the lock (approver
     * reassignment). The case's version moves on, so an edit form loaded before is refused with Reload. The lock is
     * held to the end of the caller's transaction.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @param action the action that moves it
     * @param from the status it must be in
     * @param to the status it moves to
     * @return the case and its facts under the lock
     */
    MovedCase move(Actor actor, String caseNumber, CaseAction action, CaseStatus from, CaseStatus to);
}
