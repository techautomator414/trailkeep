// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseScope;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

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
     * One case by id, if the scope reads it, for the importer's relink, which knows cases by id (WP-26 part z2).
     *
     * @param scope the caller's scope
     * @param caseId the case's id
     * @return the case, or empty
     */
    Optional<CaseRecord> findById(CaseScope scope, long caseId);

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
     * Where the next page of the export resumes: the last row's sort key and id.
     *
     * @param key the sort key as the database computed it; {@code null} for a case without a due instant
     * @param id the case id, the tiebreaker
     */
    record ExportCursor(@Nullable Object key, long id) {}

    /**
     * One page of the export.
     *
     * @param rows the rows
     * @param last the cursor of the last row, {@code null} for an empty page
     */
    record ExportPage(List<CaseExportRow> rows, @Nullable ExportCursor last) {

        /**
         * Copies the list, so the record holds nothing mutable.
         *
         * @param rows the rows
         * @param last the cursor of the last row
         */
        public ExportPage {
            rows = List.copyOf(rows);
        }
    }

    /**
     * One keyset page of the CSV export: the rows {@link #list} gives for the same query, with the subject and the
     * description, which the list never loads, strictly after a cursor in the query's order (any of the sort columns,
     * either direction, then the id ascending). The same {@code WHERE}, so a case missing from the list is missing
     * from the export, and no row committed between two pages can shift a boundary.
     *
     * @param scope the caller's scope
     * @param query the filter, with "mine" already resolved to an owner, and the order; its page is ignored
     * @param after the last row of the previous page, or {@code null} for the first page
     * @param pageSize the rows per page
     * @return the page's rows and the cursor to resume from
     */
    ExportPage export(CaseScope scope, CaseListQuery query, @Nullable ExportCursor after, int pageSize);

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

    /**
     * Several cases by id, number, archive and purge state and who changed them since their import, those the scope
     * reads, for the importer's delta and {@code reconcile}.
     *
     * @param scope the caller's scope
     * @param caseIds the ids, at most a page's worth
     * @return the readable cases, in no order
     */
    List<CaseImportService.Existing> importStates(CaseScope scope, Collection<Long> caseIds);
}
