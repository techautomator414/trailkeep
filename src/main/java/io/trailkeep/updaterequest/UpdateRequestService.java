// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.updaterequest;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseNumber;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Request Update (BUILD_PLAN §4.3, IMPLEMENTATION_PLAN A.4 #31): the "email someone" of the legacy tool, tracked on the
 * case until answered. A request goes to one person who can read the case, by mail and in-app alert with a deep link
 * and never the message; it is answered when that person comments on the case, or when the requester or a Manager
 * marks it answered. Refusals are {@code DomainException}s: 404 for an unreadable case or a request not on it, 403 if
 * the matrix denies the action, 422 for a bad message or a recipient who cannot read the case, 409 for a second open
 * request to the same person on the same case or a request already answered.
 */
public interface UpdateRequestService {

    /**
     * A request as the case page shows it.
     *
     * @param id the request's id, which "Mark answered" names
     * @param requestedBy who asked, by name
     * @param requestedFrom who was asked, by name
     * @param requestedAt when, in {@code app.timezone}, to the minute
     * @param message what was asked; possible PHI, shown on the case page only
     * @param answeredAt when it was answered, in {@code app.timezone}, or {@code null} while open
     * @param answeredBy who answered it, by name, or {@code null} while open
     * @param byComment whether the answer was a comment rather than "Mark answered"
     */
    record CaseRequest(
            long id,
            String requestedBy,
            String requestedFrom,
            LocalDateTime requestedAt,
            String message,
            @Nullable LocalDateTime answeredAt,
            @Nullable String answeredBy,
            boolean byComment) {}

    /**
     * An open request addressed to the person, for the dashboard's "Needs attention".
     *
     * @param id the request
     * @param caseNumber the case
     * @param caseTitle the case's title
     * @param requestedBy who asked, by name
     * @param requestedAt when
     */
    record OpenRequest(long id, CaseNumber caseNumber, String caseTitle, String requestedBy, Instant requestedAt) {}

    /**
     * Asks a person for an update and records {@code UPDATE_REQUESTED} with the message redacted.
     *
     * @param actor who asks; the matrix must allow {@code REQUEST_UPDATE}
     * @param caseNumber the case number as it appears in the URL
     * @param recipientId who is asked: an enabled person, not the actor, who can read the case
     * @param message 1 to 2000 characters
     * @return the request's id
     */
    long request(Actor actor, String caseNumber, long recipientId, String message);

    /**
     * Marks an open request answered and records {@code UPDATE_ANSWERED}. The requester may mark their own request
     * (as {@code REQUEST_UPDATE} allows); anyone else needs {@code ANSWER_UPDATE} (a Manager or a Compliance Admin).
     *
     * @param actor who marks it
     * @param caseNumber the case number as it appears in the URL
     * @param requestId the request; anything unreadable is {@code -1}
     */
    void markAnswered(Actor actor, String caseNumber, long requestId);

    /**
     * A case's requests, newest first; empty if the actor may not read the case.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @return the requests
     */
    List<CaseRequest> forCase(Actor actor, String caseNumber);

    /**
     * The open requests addressed to the actor on cases they read, newest first, at most 100.
     *
     * @param actor the actor
     * @return the requests
     */
    List<OpenRequest> openFor(Actor actor);
}
