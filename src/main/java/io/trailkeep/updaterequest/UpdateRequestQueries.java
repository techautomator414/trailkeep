// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.updaterequest;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseScope;
import java.util.List;

/** The scoped update-request queries; implemented with the Criteria API in {@link UpdateRequestQueriesImpl}. */
interface UpdateRequestQueries {

    /** The most open requests one list shows, far under Oracle's 1000-element {@code IN} limit for their cases. */
    int MAX_OPEN = 100;

    /**
     * A case's requests, newest first, if the scope reads the case; an unreadable and an absent case alike give none.
     *
     * @param scope the caller's scope
     * @param caseNumber the case
     * @return the requests
     */
    List<UpdateRequest> findForCase(CaseScope scope, CaseNumber caseNumber);

    /**
     * The open requests addressed to a person on cases the scope reads, newest first, at most {@link #MAX_OPEN}.
     *
     * @param scope the caller's scope
     * @param recipientId the person asked
     * @return the requests
     */
    List<UpdateRequest> findOpenFrom(CaseScope scope, long recipientId);
}
