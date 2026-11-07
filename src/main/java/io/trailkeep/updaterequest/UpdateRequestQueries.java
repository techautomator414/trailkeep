// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.updaterequest;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseScope;
import java.util.List;

/** The scoped update-request queries; implemented with the Criteria API in {@link UpdateRequestQueriesImpl}. */
interface UpdateRequestQueries {

    /**
     * A case's requests, newest first, if the scope reads the case; an unreadable and an absent case alike give none.
     *
     * @param scope the caller's scope
     * @param caseNumber the case
     * @return the requests
     */
    List<UpdateRequest> findForCase(CaseScope scope, CaseNumber caseNumber);

    /**
     * One page of the open requests addressed to a person on cases the scope reads, newest first.
     *
     * @param scope the caller's scope
     * @param recipientId the person asked
     * @param offset the page's first row, from 0
     * @param limit the page size
     * @return the requests
     */
    List<UpdateRequest> findOpenFrom(CaseScope scope, long recipientId, int offset, int limit);

    /**
     * How many open requests are addressed to a person on cases the scope reads.
     *
     * @param scope the caller's scope
     * @param recipientId the person asked
     * @return the count
     */
    long countOpenFrom(CaseScope scope, long recipientId);

    /**
     * The open requests on cases the scope reads, whoever was asked, oldest first.
     *
     * @param scope the caller's scope
     * @param limit the most to return
     * @return the requests
     */
    List<UpdateRequest> findOpen(CaseScope scope, int limit);
}
