// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseScope;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** The scoped case queries; implemented with the Criteria API in {@link CaseRecordQueriesImpl}. */
interface CaseRecordQueries {

    /**
     * One case, if the scope reads it. An unreadable case and an absent one give the same answer.
     *
     * @param scope the caller's scope
     * @param caseNumber the case number
     * @return the case, or empty
     */
    Optional<CaseRecord> findOne(CaseScope scope, CaseNumber caseNumber);

    /**
     * Whether the scope reads a case, without loading it.
     *
     * @param scope the caller's scope
     * @param caseNumber the case number
     * @return {@code true} if the case exists and the scope reads it
     */
    boolean exists(CaseScope scope, CaseNumber caseNumber);

    /**
     * One case with what its page needs from the case type and the SLA row, in one statement, if the scope reads
     * it. An unreadable case and an absent one give the same answer.
     *
     * @param scope the caller's scope
     * @param caseNumber the case number
     * @return the case page's row, or empty
     */
    Optional<CaseRow> findRow(CaseScope scope, CaseNumber caseNumber);

    /**
     * How many cases the scope reads that match a filter. The same predicate as {@link #list}, so a case missing
     * from the rows is missing from the count.
     *
     * @param scope the caller's scope
     * @param filter the filter, with "mine" already resolved to an owner
     * @return the number of matching readable cases
     */
    long count(CaseScope scope, CaseFilter filter);

    /**
     * One page of the cases the scope reads that match a query's filter, in its order, with the case type's name and
     * the SLA state, in one statement. Ties are broken by id, so pages never overlap.
     *
     * @param scope the caller's scope
     * @param query the filter, with "mine" already resolved to an owner, order and page
     * @param pageSize the rows per page
     * @return the page's rows
     */
    List<CaseListRow> list(CaseScope scope, CaseListQuery query, int pageSize);

    /**
     * One page of the CSV export: the rows {@link #list} gives for the same query, page and size, with the subject and
     * the description, which the list never loads. The same {@code WHERE}, so a case missing from the list is missing
     * from the export.
     *
     * @param scope the caller's scope
     * @param query the filter, with "mine" already resolved to an owner, order and page
     * @param pageSize the rows per page
     * @return the page's rows
     */
    List<CaseExportRow> export(CaseScope scope, CaseListQuery query, int pageSize);

    /**
     * Every distinct team and owner pair of the cases the scope reads, archived ones included, for the filters.
     *
     * @param scope the caller's scope
     * @return the pairs, in no order
     */
    List<CaseListRow.TeamOwner> teamsAndOwners(CaseScope scope);

    /**
     * Several cases by id, number and title only, those the scope reads; unreadable and unknown ids give nothing.
     *
     * @param scope the caller's scope
     * @param caseIds the ids, at most a page's worth
     * @return the readable cases, in no order
     */
    List<CaseService.CaseRef> refs(CaseScope scope, Collection<Long> caseIds);
}
