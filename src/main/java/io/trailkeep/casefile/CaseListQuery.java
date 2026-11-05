// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

/**
 * One request for the cases list: what to show, in which order, and which page.
 *
 * @param filter the filter
 * @param sort the column to sort by
 * @param descending whether the sort is descending
 * @param page the page, from 1; a smaller value means 1, and a page past the last shows the last
 */
public record CaseListQuery(CaseFilter filter, CaseSort sort, boolean descending, int page) {

    /** Raises a page below 1 to 1. */
    public CaseListQuery {
        page = Math.max(page, 1);
    }

    /**
     * The first page of a filter in the default order, newest first.
     *
     * @param filter the filter
     * @return the query
     */
    public static CaseListQuery of(CaseFilter filter) {
        return new CaseListQuery(filter, CaseSort.OPENED, CaseSort.OPENED.descendingFirst(), 1);
    }

    /**
     * The same query on another page.
     *
     * @param other the page, from 1
     * @return the query
     */
    public CaseListQuery onPage(int other) {
        return new CaseListQuery(filter, sort, descending, other);
    }

    /**
     * What a click on a column header asks for: the current column in the other direction, or another column in its
     * first direction, and in both cases page 1.
     *
     * @param column the column clicked
     * @return the query
     */
    public CaseListQuery sortedBy(CaseSort column) {
        return new CaseListQuery(filter, column, column == sort ? !descending : column.descendingFirst(), 1);
    }
}
