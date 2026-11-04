// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import io.trailkeep.shared.access.CaseScope;
import java.util.List;

/** The scoped audit queries; implemented with the Criteria API in {@link AuditEventQueriesImpl}. */
interface AuditEventQueries {

    /**
     * The newest events visible to the scope.
     *
     * @param scope the caller's scope
     * @param limit the maximum number of rows
     * @return the events, newest first
     */
    List<AuditEvent> findRecent(CaseScope scope, int limit);

    /**
     * The newest events of one case visible to the scope.
     *
     * @param scope the caller's scope
     * @param caseId the case id
     * @param limit the maximum number of rows
     * @return the events, newest first
     */
    List<AuditEvent> findByCase(CaseScope scope, long caseId, int limit);
}
