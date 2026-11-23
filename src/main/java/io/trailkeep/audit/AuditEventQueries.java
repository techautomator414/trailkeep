// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import io.trailkeep.shared.access.CaseScope;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

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

    /**
     * One page of the events of one case visible to the scope, optionally only some actions.
     *
     * @param scope the caller's scope
     * @param caseId the case id
     * @param actions the actions to keep; empty keeps every action
     * @param offset how many of the newest events to skip
     * @param limit the maximum number of rows
     * @return the events, newest first
     */
    List<AuditEvent> pageByCase(CaseScope scope, long caseId, Set<String> actions, int offset, int limit);

    /**
     * How many events {@link #pageByCase} pages through.
     *
     * @param scope the caller's scope
     * @param caseId the case id
     * @param actions the actions to keep; empty keeps every action
     * @return the count
     */
    long countByCase(CaseScope scope, long caseId, Set<String> actions);

    /**
     * One page of the events a filter keeps, case-less ones included; empty unless the scope is unrestricted.
     *
     * @param scope the caller's scope
     * @param filter the filter
     * @param offset how many of the newest events to skip
     * @param limit the maximum number of rows
     * @return the events, newest first
     */
    List<AuditEvent> search(CaseScope scope, AuditTrailService.AuditFilter filter, int offset, int limit);

    /**
     * How many events {@link #search} pages through.
     *
     * @param scope the caller's scope
     * @param filter the filter
     * @return the count
     */
    long countSearch(CaseScope scope, AuditTrailService.AuditFilter filter);

    /**
     * How many events a filter keeps among those with an id up to a bound.
     *
     * @param scope the caller's scope
     * @param filter the filter
     * @param upToId the highest id to count
     * @return the count
     */
    long countSearch(CaseScope scope, AuditTrailService.AuditFilter filter, long upToId);

    /**
     * The highest id of any event, or 0 if there is none; empty unless the scope is unrestricted.
     *
     * @param scope the caller's scope
     * @return the id
     */
    long newestId(CaseScope scope);

    /**
     * One keyset page of the events a filter keeps, newest first: those with an id up to a bound and, if a position is
     * given, strictly after it in the order of {@code AT_TS} then {@code ID}, both descending.
     *
     * @param scope the caller's scope
     * @param filter the filter
     * @param upToId the highest id to read
     * @param after the last event of the previous page, or {@code null} for the first page
     * @param limit the maximum number of rows
     * @return the events, newest first
     */
    List<AuditEvent> searchAfter(
            CaseScope scope,
            AuditTrailService.AuditFilter filter,
            long upToId,
            AuditTrailService.@Nullable Position after,
            int limit);
}
