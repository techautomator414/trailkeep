// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.html.SafeHtml;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Comments on the case page (BUILD_PLAN §4.3): read oldest first through the actor's {@code CaseScope}, and posted
 * after the same refusals as every other action (404, then 403). A comment is never edited or deleted. Refusals are
 * {@link CaseException}s.
 */
public interface CaseCommentService {

    /**
     * A comment as the page shows it.
     *
     * @param author the author's display name when they wrote it
     * @param writtenAt when, in {@code app.timezone}, to the minute
     * @param body the Markdown rendered and sanitized; the only form in which a comment's text leaves this module
     */
    record CommentView(String author, LocalDateTime writtenAt, SafeHtml body) {}

    /**
     * A case's comments, oldest first; empty if there are none or the actor may not read the case.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @return the comments
     */
    List<CommentView> comments(Actor actor, String caseNumber);

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
}
