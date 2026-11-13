// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import io.trailkeep.shared.access.CaseScope;
import java.util.List;

/** The scoped imported-history queries; implemented with the Criteria API in {@link CaseImportedEventQueriesImpl}. */
interface CaseImportedEventQueries {

    /**
     * One page of a case's imported events visible to the scope, newest legacy time first, then newest row first.
     *
     * @param scope the caller's scope
     * @param caseId the case id
     * @param offset how many of the newest events to skip
     * @param limit the maximum number of rows
     * @return the events; empty if the scope may not read the case
     */
    List<CaseImportedEvent> pageByCase(CaseScope scope, long caseId, int offset, int limit);

    /**
     * How many events {@link #pageByCase} pages through.
     *
     * @param scope the caller's scope
     * @param caseId the case id
     * @return the count; zero if the scope may not read the case
     */
    long countByCase(CaseScope scope, long caseId);
}
