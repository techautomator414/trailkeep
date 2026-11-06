// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.closure;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseScope;
import java.util.List;

/** The scoped closure-request queries; implemented with the Criteria API in {@link ClosureRequestQueriesImpl}. */
interface ClosureRequestQueries {

    /**
     * A case's closure requests, newest first, if the scope reads the case; an unreadable and an absent case alike
     * give none.
     *
     * @param scope the caller's scope
     * @param caseNumber the case
     * @return the requests
     */
    List<ClosureRequest> findForCase(CaseScope scope, CaseNumber caseNumber);
}
