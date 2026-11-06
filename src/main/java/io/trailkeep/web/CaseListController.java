// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseFilter;
import io.trailkeep.casefile.CaseList;
import io.trailkeep.casefile.CaseListQuery;
import io.trailkeep.casefile.CaseListService;
import io.trailkeep.casefile.CasePriority;
import io.trailkeep.casefile.CaseService.Choice;
import io.trailkeep.casefile.CaseSort;
import io.trailkeep.casefile.SlaState;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The cases list (BUILD_PLAN §4.4): a GET with the filter, order and page in the query string, read by {@link
 * CaseListQuery#parse}, which answers 422 for a value no form offers (an unknown sort column, for one). Which cases
 * are counted and listed is decided by {@link CaseListService} through the person's scope. The filters sit in a
 * native {@code <dialog>}; {@code filters=1} renders it open, which is how the "Filters" link works without
 * JavaScript. An htmx request (a sort header, a page link, a filter chip or the filter form) gets the results
 * fragment and, out of band, fresh filter controls; app.js then moves focus to the control that asked for it, found
 * by the {@code HX-Trigger} id, or else to the status line, and announces the count.
 */
@Controller
class CaseListController {

    /** The ids the results fragment gives its sort headers and page links. */
    private static final Pattern TRIGGER = Pattern.compile("tk-(sort-[a-z]+|page-(previous|next))");

    /** The sort columns by name, so the template names each header's column without a static call. */
    private static final Map<String, CaseSort> COLUMNS =
            Arrays.stream(CaseSort.values()).collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));

    /** The message key prefix that names a chip's value, per criterion whose values are enum constants. */
    private static final Map<String, String> VALUE_KEYS = Map.of(
            "status", "tk.case.status.",
            "sla", "tk.list.sla.",
            "priority", "tk.new.priority.",
            "sensitivity", "tk.list.sensitivity.");

    private final CaseListService cases;
    private final CasePage page;

    CaseListController(CaseListService cases, CasePage page) {
        this.cases = cases;
        this.page = page;
    }

    /**
     * An active filter criterion as a removable chip.
     *
     * @param name the criterion's URL parameter, which names its label
     * @param key the message key of the value's text, or empty to show {@code value} as it is
     * @param value the value's text when {@code key} is empty: the search text, a date, or a name
     * @param without the URL query parameters of the list without this criterion
     */
    public record Chip(String name, String key, String value, String without) {}

    @GetMapping("/cases")
    String list(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @RequestParam MultiValueMap<String, String> parameters,
            HttpServletRequest request,
            Model model) {
        CaseListQuery query = CaseListQuery.parse(parameters);
        CaseList list = cases.list(page.actor(principal), query);
        model.addAttribute("query", query);
        model.addAttribute("list", list);
        model.addAttribute("chips", chips(query, list));
        model.addAttribute("columns", COLUMNS);
        model.addAttribute("statuses", CaseStatus.values());
        model.addAttribute("slaStates", SlaState.values());
        model.addAttribute("priorities", CasePriority.values());
        model.addAttribute("sensitivities", Sensitivity.values());
        model.addAttribute("maxText", CaseFilter.MAX_TEXT);
        if (!"true".equals(request.getHeader("HX-Request"))) {
            model.addAttribute("filtersOpen", "1".equals(parameters.getFirst("filters")));
            return "casefile/cases";
        }
        String trigger = request.getHeader("HX-Trigger");
        model.addAttribute("focus", trigger != null && TRIGGER.matcher(trigger).matches() ? trigger : "");
        return "casefile/fragments/case-swap";
    }

    /**
     * One chip per criterion. A case type, team or owner is named from the choices the list offers; an id that is
     * not among them, typed into the URL, shows as the id, so a chip never names anything the scope does not read.
     */
    private static List<Chip> chips(CaseListQuery query, CaseList list) {
        Map<String, String> names = new HashMap<>();
        name(names, "type", list.caseTypes());
        name(names, "team", list.teams());
        name(names, "owner", list.owners());
        return query.criteria().stream()
                .map(criterion -> {
                    String prefix = "me".equals(criterion.value()) && "owner".equals(criterion.name())
                            ? "tk.list.owner."
                            : VALUE_KEYS.get(criterion.name());
                    String key = criterion.name() + "=" + criterion.value();
                    return prefix == null
                            ? new Chip(
                                    criterion.name(),
                                    "",
                                    names.getOrDefault(key, criterion.value()),
                                    criterion.without())
                            : new Chip(criterion.name(), prefix + criterion.value(), "", criterion.without());
                })
                .toList();
    }

    private static void name(Map<String, String> names, String parameter, List<Choice> choices) {
        choices.forEach(choice -> names.put(parameter + "=" + choice.id(), choice.name()));
    }
}
