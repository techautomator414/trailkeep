// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseNumber;
import java.time.Instant;

/**
 * Published in the transaction that posted a comment, after the comment and its audit event are written, so modules
 * that react to comments (such as {@code updaterequest}, which answers the author's open update requests) listen
 * {@code BEFORE_COMMIT} and need no dependency from {@code casefile}. It carries no comment text.
 *
 * @param caseId the case's id
 * @param caseNumber the case number
 * @param commentId the new comment
 * @param authorId who wrote it
 * @param authorName their display name, for the audit trail of a reaction
 * @param postedAt when
 */
public record CommentPosted(
        long caseId, CaseNumber caseNumber, long commentId, long authorId, String authorName, Instant postedAt) {}
