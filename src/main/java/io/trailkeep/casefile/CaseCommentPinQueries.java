// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseScope;
import java.util.List;
import java.util.Optional;

/** The scoped pin queries; implemented with the Criteria API in {@link CaseCommentPinQueriesImpl}. */
interface CaseCommentPinQueries {

    /**
     * A case's pins, if the scope reads the case. An unreadable case and an absent one give the same answer.
     *
     * @param scope the caller's scope
     * @param caseNumber the case number
     * @return the pins, in no particular order, or an empty list
     */
    List<CaseCommentPin> findForCase(CaseScope scope, CaseNumber caseNumber);

    /**
     * The pin of one comment of a case, if the scope reads the case and that comment of that case is pinned.
     *
     * @param scope the caller's scope
     * @param caseNumber the case number
     * @param commentId the comment
     * @return the pin, or empty
     */
    Optional<CaseCommentPin> findOne(CaseScope scope, CaseNumber caseNumber, long commentId);
}
