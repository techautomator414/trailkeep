// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

/**
 * The columns the cases list sorts by. This is the allow-list (Part B "Web"): each constant maps to one attribute in
 * {@code CaseRecordQueriesImpl}, and a request naming anything else gets the default order. Status and case type are
 * not here because their stored names do not sort in a meaningful order.
 */
public enum CaseSort {
    /** By case number. */
    NUMBER(false),
    /** By title. */
    TITLE(false),
    /** By priority; ascending puts High first. */
    PRIORITY(false),
    /** By SLA due date, soonest first; cases without one come last. */
    SLA_DUE(false),
    /** By opening instant, newest first. The default. */
    OPENED(true);

    private final boolean descendingFirst;

    CaseSort(boolean descendingFirst) {
        this.descendingFirst = descendingFirst;
    }

    /**
     * The direction the first click on this column sorts in.
     *
     * @return {@code true} for descending
     */
    public boolean descendingFirst() {
        return descendingFirst;
    }
}
