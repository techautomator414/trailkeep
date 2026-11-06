// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Sensitivity;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * One request for the cases list: what to show, in which order, and which page. It is read from and written back to
 * the URL's query parameters ({@code q}, {@code status} and {@code sla} (both repeatable), {@code priority},
 * {@code sensitivity}, {@code from} and {@code to} (ISO dates), {@code type}, {@code team}, {@code owner},
 * {@code ids} (repeatable case numbers, the rows selected for export), {@code sort}, {@code dir}, {@code page}), so a
 * list can be bookmarked and the browser's Back button restores it.
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
     * One criterion of the filter in force, as a removable chip shows it.
     *
     * @param name the URL parameter, such as {@code status}
     * @param value the value as the URL carries it, such as {@code OPEN}, an id or an ISO date
     * @param without this query, on page 1, without just this criterion, as URL query parameters
     */
    public record Criterion(String name, String value, String without) {}

    /**
     * Reads a query from request parameters. Parameters it does not know are ignored; a known one with a value no
     * form offers (an unknown status, SLA state or column, a non-numeric id, a date that is not ISO, a date range
     * that ends before it starts, text over {@link CaseFilter#MAX_TEXT}) is refused.
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
        String owner = first(parameters, "owner");
        boolean mine = "me".equals(owner);
        return new CaseFilter(
                constants(parameters, "status", CaseStatus.class),
                constants(parameters, "sla", SlaState.class),
                optional(parameters, "priority", CasePriority.class),
                optional(parameters, "sensitivity", Sensitivity.class),
                date(first(parameters, "from")),
                date(first(parameters, "to")),
                id(first(parameters, "type")),
                id(first(parameters, "team")),
                mine ? null : id(owner),
                mine,
                text == null ? "" : text,
                numbers(parameters.getOrDefault("ids", List.of())));
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
        List<Map.Entry<String, String>> out = filterParameters();
        out.addAll(orderParameters());
        add(out, "page", page == 1 ? null : page);
        return encoded(out);
    }

    /**
     * The criteria of the filter in force, one per status and SLA state, in the order of {@link #toQueryString}. Each
     * knows the query without it, so removing a chip keeps the other criteria and the order and returns to page 1.
     *
     * @return the criteria, empty when the filter restricts nothing
     */
    public List<Criterion> criteria() {
        List<Map.Entry<String, String>> parameters = filterParameters();
        List<Criterion> criteria = new ArrayList<>(parameters.size());
        for (int i = 0; i < parameters.size(); i++) {
            List<Map.Entry<String, String>> rest = new ArrayList<>(parameters);
            Map.Entry<String, String> removed = rest.remove(i);
            rest.addAll(orderParameters());
            criteria.add(new Criterion(removed.getKey(), removed.getValue(), encoded(rest)));
        }
        return List.copyOf(criteria);
    }

    /**
     * The filter and the order as URL parameters, the page aside, in the order of {@link #toQueryString}: what a form
     * repeating this list (the CSV export) carries as hidden fields.
     *
     * @return the parameters, possibly empty
     */
    public List<Map.Entry<String, String>> parameters() {
        List<Map.Entry<String, String>> out = filterParameters();
        out.addAll(orderParameters());
        return List.copyOf(out);
    }

    private List<Map.Entry<String, String>> filterParameters() {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        add(out, "q", filter.text().isEmpty() ? null : filter.text());
        filter.statuses().stream().sorted().forEach(status -> add(out, "status", status));
        filter.slaStates().stream().sorted().forEach(state -> add(out, "sla", state));
        add(out, "priority", filter.priority());
        add(out, "sensitivity", filter.sensitivity());
        add(out, "from", filter.identifiedFrom());
        add(out, "to", filter.identifiedTo());
        add(out, "type", filter.caseTypeId());
        add(out, "team", filter.teamId());
        add(out, "owner", filter.mine() ? "me" : filter.ownerId());
        filter.numbers().stream().sorted().forEach(number -> add(out, "ids", number));
        return out;
    }

    private List<Map.Entry<String, String>> orderParameters() {
        List<Map.Entry<String, String>> out = new ArrayList<>(2);
        if (sort != CaseSort.OPENED || descending != sort.descendingFirst()) {
            add(out, "sort", sort.name().toLowerCase(Locale.ROOT));
            add(out, "dir", descending ? "desc" : "asc");
        }
        return out;
    }

    private static void add(List<Map.Entry<String, String>> out, String name, @Nullable Object value) {
        if (value != null) {
            out.add(Map.entry(name, value.toString()));
        }
    }

    private static String encoded(List<Map.Entry<String, String>> parameters) {
        return parameters.stream()
                .map(parameter -> parameter.getKey() + "=" + URLEncoder.encode(parameter.getValue(), UTF_8))
                .collect(Collectors.joining("&"));
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

    /** The selected case numbers, each canonical; a malformed one, or more than a page could show, is refused. */
    private static Set<String> numbers(List<String> values) {
        if (values.size() > CaseFilter.MAX_NUMBERS) {
            throw invalid("too many selected cases");
        }
        try {
            return values.stream()
                    .filter(value -> !value.isBlank())
                    .map(value -> CaseNumber.parse(value.strip()).toString())
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException malformed) {
            throw invalid("not a case number");
        }
    }

    private static @Nullable LocalDate date(@Nullable String value) {
        try {
            return value == null ? null : LocalDate.parse(value.strip());
        } catch (DateTimeParseException malformed) {
            throw invalid("not an ISO date");
        }
    }

    /** Every non-blank value of a repeatable parameter, each one constant of the enum. */
    private static <E extends Enum<E>> Set<E> constants(
            Map<String, List<String>> parameters, String name, Class<E> type) {
        return parameters.getOrDefault(name, List.of()).stream()
                .filter(value -> !value.isBlank())
                .map(value -> constant(type, value))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(type)));
    }

    private static <E extends Enum<E>> @Nullable E optional(
            Map<String, List<String>> parameters, String name, Class<E> type) {
        String value = first(parameters, name);
        return value == null ? null : constant(type, value);
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
