// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseScope;
import java.util.List;

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
}
