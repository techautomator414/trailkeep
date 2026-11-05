// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException.Kind;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;

/**
 * One request for the cases list: what to show, in which order, and which page. It is read from and written back to
 * the URL's query parameters ({@code q}, {@code status}, {@code type}, {@code team}, {@code owner}, {@code sort},
 * {@code dir}, {@code page}), so a list can be bookmarked and the browser's Back button restores it.
 *
 * @param filter the filter
 * @param sort the column to sort by
 * @param descending whether the sort is descending
 * @param page the page, from 1; a page past the last shows the last
 */
public record CaseListQuery(CaseFilter filter, CaseSort sort, boolean descending, int page) {

    /** Raises a page below 1 to 1. */
    public CaseListQuery {
        page = Math.max(page, 1);
    }

    /**
     * Reads a query from request parameters. Parameters it does not know are ignored; a known one with a value no
     * form offers (an unknown status or column, a non-numeric id, text over {@link CaseFilter#MAX_TEXT}) is refused.
     *
     * @param parameters the parameters, each with its values
     * @return the query
     * @throws CaseException {@code INVALID} for a value no form offers
     */
    public static CaseListQuery parse(Map<String, List<String>> parameters) {
        CaseFilter filter = filter(parameters);
        CaseSort sort = CaseSort.parse(first(parameters, "sort"));
        String dir = first(parameters, "dir");
        boolean descending = dir == null ? sort.descendingFirst() : "desc".equals(dir);
        if (dir != null && !descending && !"asc".equals(dir)) {
            throw invalid("unknown sort direction");
        }
        String page = first(parameters, "page");
        return new CaseListQuery(
                filter, sort, descending, page == null ? 1 : (int) Math.min(number(page), Integer.MAX_VALUE));
    }

    private static CaseFilter filter(Map<String, List<String>> parameters) {
        String text = first(parameters, "q");
        if (text != null && text.strip().length() > CaseFilter.MAX_TEXT) {
            throw invalid("search text too long");
        }
        Set<CaseStatus> statuses = EnumSet.noneOf(CaseStatus.class);
        for (String status : parameters.getOrDefault("status", List.of())) {
            if (!status.isBlank()) {
                statuses.add(constant(CaseStatus.class, status));
            }
        }
        String owner = first(parameters, "owner");
        boolean mine = "me".equals(owner);
        return new CaseFilter(
                statuses,
                id(first(parameters, "type")),
                id(first(parameters, "team")),
                mine ? null : id(owner),
                mine,
                text == null ? "" : text);
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

    /**
     * This query as URL query parameters, without the {@code ?}; parameters at their default are left out, so
     * {@link #parse} reads back an equal query.
     *
     * @return the encoded parameters, possibly empty
     */
    public String toQueryString() {
        StringJoiner out = new StringJoiner("&");
        add(out, "q", filter.text().isEmpty() ? null : filter.text());
        filter.statuses().stream().sorted().forEach(status -> add(out, "status", status.name()));
        add(out, "type", filter.caseTypeId());
        add(out, "team", filter.teamId());
        add(out, "owner", filter.mine() ? "me" : filter.ownerId());
        if (sort != CaseSort.OPENED || descending != sort.descendingFirst()) {
            add(out, "sort", sort.name().toLowerCase(Locale.ROOT));
            add(out, "dir", descending ? "desc" : "asc");
        }
        add(out, "page", page == 1 ? null : page);
        return out.toString();
    }

    private static void add(StringJoiner out, String name, @Nullable Object value) {
        if (value != null) {
            out.add(name + "=" + URLEncoder.encode(value.toString(), StandardCharsets.UTF_8));
        }
    }

    private static @Nullable String first(Map<String, List<String>> parameters, String name) {
        List<String> values = parameters.getOrDefault(name, List.of());
        return values.isEmpty() || values.get(0).isBlank() ? null : values.get(0);
    }

    private static @Nullable Long id(@Nullable String value) {
        return value == null ? null : number(value);
    }

    private static long number(String value) {
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException malformed) {
            throw invalid("not a number");
        }
    }

    private static <E extends Enum<E>> E constant(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw invalid("unknown " + type.getSimpleName());
        }
    }

    private static CaseException invalid(String message) {
        return new CaseException(Kind.INVALID, message);
    }
}
