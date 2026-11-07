// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.closure;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseScope;
import java.util.List;

/** The scoped case-reopen queries; implemented with the Criteria API in {@link CaseReopenQueriesImpl}. */
interface CaseReopenQueries {

    /**
     * A case's reopens, newest first, if the scope reads the case; an unreadable and an absent case alike give none.
     *
     * @param scope the caller's scope
     * @param caseNumber the case
     * @return the reopens
     */
    List<CaseReopen> findForCase(CaseScope scope, CaseNumber caseNumber);
}
