// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseScope;
import java.util.List;
import java.util.Optional;

/** The scoped comment queries; implemented with the Criteria API in {@link CaseCommentQueriesImpl}. */
interface CaseCommentQueries {

    /**
     * A case's comments, oldest first, if the scope reads the case. An unreadable case and an absent one give the
     * same answer.
     *
     * @param scope the caller's scope
     * @param caseNumber the case number
     * @return the comments in the order they were written, or an empty list
     */
    List<CaseComment> findForCase(CaseScope scope, CaseNumber caseNumber);

    /**
     * One comment of a case, if the scope reads the case and the comment belongs to it. An unreadable case, an absent
     * one and a comment of another case give the same answer.
     *
     * @param scope the caller's scope
     * @param caseNumber the case number
     * @param commentId the comment
     * @return the comment, or empty
     */
    Optional<CaseComment> findOne(CaseScope scope, CaseNumber caseNumber, long commentId);
}
