// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.html.SafeHtml;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Comments on the case page (BUILD_PLAN §4.3): read oldest first through the actor's {@code CaseScope}, posted, and
 * pinned or unpinned after the same refusals as every other action (404, then 403). A comment is never edited or
 * deleted; a pin is a separate row. Refusals are {@link CaseException}s.
 */
public interface CaseCommentService {

    /**
     * A comment as the page shows it.
     *
     * @param id the comment's id, which a pin or unpin form names
     * @param author the author's display name when they wrote it
     * @param writtenAt when, in {@code app.timezone}, to the minute
     * @param body the Markdown rendered and sanitized; the only form in which a comment's text leaves this module
     * @param pinned whether the comment is pinned
     */
    record CommentView(long id, String author, LocalDateTime writtenAt, SafeHtml body, boolean pinned) {}

    /**
     * A case's comments, oldest first; empty if there are none or the actor may not read the case.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @return the comments
     */
    List<CommentView> comments(Actor actor, String caseNumber);

    /**
     * When the case's latest comment was posted, exactly (the closure module's "Requires Changes until the next
     * comment"); empty if there is none or the actor may not read the case, {@code NOT_FOUND} for a malformed number.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @return the instant, or empty
     */
    Optional<Instant> lastCommentAt(Actor actor, String caseNumber);

    /**
     * Posts a comment with the actor's display name as its author, appends its text to the search text and records
     * {@code COMMENT_ADDED} with the body redacted. {@code NOT_FOUND} for a malformed, absent or unreadable case;
     * {@code FORBIDDEN} if the matrix denies {@code COMMENT}; then {@code INVALID} for a blank body or one longer
     * than 20 000 characters (line breaks count as one).
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @param body the Markdown as typed
     */
    void post(Actor actor, String caseNumber, String body);

    /**
     * Pins a comment of the case, or unpins it, and records {@code COMMENT_PINNED} or {@code COMMENT_UNPINNED}; asking
     * for the state the comment is already in changes and records nothing. {@code NOT_FOUND} for a malformed, absent
     * or unreadable case; {@code FORBIDDEN} if the matrix denies {@code PIN}; then {@code INVALID} if the id names no
     * comment of this case.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @param commentId the comment, as submitted; anything unreadable is {@code -1}
     * @param pinned {@code true} to pin, {@code false} to unpin
     */
    void setPinned(Actor actor, String caseNumber, long commentId, boolean pinned);
}
