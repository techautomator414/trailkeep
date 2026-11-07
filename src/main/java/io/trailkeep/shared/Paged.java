// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared;

import java.util.List;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

/**
 * One page of a list shown with plain previous and next links (the dashboard's "Needs attention", WP-20).
 *
 * @param page the page shown, from 1
 * @param pages how many pages there are, at least 1
 * @param total how many rows there are
 * @param rows the rows of the page
 * @param <T> the row type
 */
public record Paged<T>(int page, int pages, long total, List<T> rows) {

    /**
     * Copies the list, so the record holds nothing mutable.
     *
     * @param page the page shown
     * @param pages how many pages there are
     * @param total how many rows there are
     * @param rows the rows of the page
     */
    public Paged {
        rows = List.copyOf(rows);
    }

    /**
     * Reads one page: the count first, then the rows from the page's first offset; a page past the last shows the last.
     *
     * @param page the page asked for, from 1
     * @param size rows per page
     * @param count counts the rows
     * @param rows reads the rows from an offset
     * @param <T> the row type
     * @return the page
     */
    public static <T> Paged<T> of(int page, int size, LongSupplier count, IntFunction<List<T>> rows) {
        long total = count.getAsLong();
        int pages = (int) Math.max(1, Math.min((total + size - 1) / size, Integer.MAX_VALUE));
        int shown = Math.min(Math.max(page, 1), pages);
        return new Paged<>(shown, pages, total, total == 0 ? List.of() : rows.apply((shown - 1) * size));
    }
}
