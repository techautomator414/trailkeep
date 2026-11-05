// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.DomainException.Kind;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;

/**
 * One request for the cases list: its order and page. It is read from and written back to the URL's query parameters
 * ({@code sort}, {@code dir}, {@code page}), so a list can be bookmarked and the browser's Back button restores it.
 * Filters and search join it in WP-15 part 1b.
 *
 * @param sort the column to sort by
 * @param descending whether the sort is descending
 * @param page the page, from 1; a page past the last shows the last
 */
public record CaseListQuery(CaseSort sort, boolean descending, int page) {

    /** Raises a page below 1 to 1. */
    public CaseListQuery {
        page = Math.max(page, 1);
    }

    /**
     * Reads a query from request parameters. Parameters it does not know are ignored; a known one with a value no
     * page offers (a column outside the allow-list, a direction other than {@code asc} or {@code desc}, a page that is
     * not a number) is refused.
     *
     * @param parameters the parameters, each with its values
     * @return the query
     * @throws CaseException {@code INVALID} for a value no page offers
     */
    public static CaseListQuery parse(Map<String, List<String>> parameters) {
        CaseSort sort = CaseSort.parse(first(parameters, "sort"));
        String dir = first(parameters, "dir");
        boolean descending = dir == null ? sort.descendingFirst() : "desc".equals(dir);
        if (dir != null && !descending && !"asc".equals(dir)) {
            throw invalid("unknown sort direction");
        }
        String page = first(parameters, "page");
        return new CaseListQuery(sort, descending, page == null ? 1 : (int) Math.min(number(page), Integer.MAX_VALUE));
    }

    /**
     * The same order on another page.
     *
     * @param other the page, from 1
     * @return the query
     */
    public CaseListQuery onPage(int other) {
        return new CaseListQuery(sort, descending, other);
    }

    /**
     * What a click on a column header asks for: the current column in the other direction, or another column in its
     * first direction, and in both cases page 1.
     *
     * @param column the column clicked
     * @return the query
     */
    public CaseListQuery sortedBy(CaseSort column) {
        return new CaseListQuery(column, column == sort ? !descending : column.descendingFirst(), 1);
    }

    /**
     * This query as URL query parameters, without the {@code ?}; parameters at their default are left out, so
     * {@link #parse} reads back an equal query.
     *
     * @return the parameters, possibly empty; every value is an enum name or a number, so none needs encoding
     */
    public String toQueryString() {
        StringJoiner out = new StringJoiner("&");
        if (sort != CaseSort.OPENED || descending != sort.descendingFirst()) {
            out.add("sort=" + sort.name().toLowerCase(Locale.ROOT));
            out.add("dir=" + (descending ? "desc" : "asc"));
        }
        if (page != 1) {
            out.add("page=" + page);
        }
        return out.toString();
    }

    private static @Nullable String first(Map<String, List<String>> parameters, String name) {
        List<String> values = parameters.getOrDefault(name, List.of());
        return values.isEmpty() || values.get(0).isBlank() ? null : values.get(0);
    }

    private static long number(String value) {
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException malformed) {
            throw invalid("not a number");
        }
    }

    private static CaseException invalid(String message) {
        return new CaseException(Kind.INVALID, message);
    }
}
