// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.updaterequest;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseNumber;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

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
     * An open request as the case page shows it ("Update requested from X on date: awaiting"); answered requests are
     * on the History page.
     *
     * @param id the request's id, which "Mark answered" names
     * @param requestedById who asked, so the page knows whether the reader may mark it as its requester
     * @param requestedBy who asked, by name
     * @param requestedFrom who was asked, by name
     * @param requestedAt when, in {@code app.timezone}, to the minute
     * @param message what was asked; possible PHI, shown on the case page only
     * @param answerable whether the reader may mark it answered (its requester, or a Manager or Compliance Admin)
     */
    record CaseRequest(
            long id,
            long requestedById,
            String requestedBy,
            String requestedFrom,
            LocalDateTime requestedAt,
            String message,
            boolean answerable) {}

    /**
     * Someone an update may be requested from, as the dialog's combobox offers them.
     *
     * @param id the person's id
     * @param name their display name
     */
    record Recipient(long id, String name) {}

    /**
     * The people an update may be requested from on a case (IMPLEMENTATION_PLAN A.4 #31): the team's members and the
     * Compliance Admins who are enabled and read the case as their roles stand now, minus the actor, by name. The
     * server checks again on {@link #request}. {@code NOT_FOUND}, then {@code FORBIDDEN} without
     * {@code REQUEST_UPDATE}.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @return the people, by name
     */
    List<Recipient> recipients(Actor actor, String caseNumber);

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
     * A case's open requests, newest first; {@code NOT_FOUND} if the actor may not read the case.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @return the open requests
     */
    List<CaseRequest> openForCase(Actor actor, String caseNumber);

    /**
     * The open requests addressed to the actor on cases they read, newest first, at most 100.
     *
     * @param actor the actor
     * @return the requests
     */
    List<OpenRequest> openFor(Actor actor);
}
