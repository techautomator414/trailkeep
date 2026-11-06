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

    /**
     * The pending requests a person is the approver of, on cases the scope reads, oldest first, at most 100 (the
     * dashboard's "Pending my approval").
     *
     * @param scope the caller's scope
     * @param approverId the approver
     * @return the requests
     */
    List<ClosureRequest> findPendingFor(CaseScope scope, long approverId);
}
