// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseExportService;
import io.trailkeep.casefile.CaseFilter;
import io.trailkeep.casefile.CaseList;
import io.trailkeep.casefile.CaseListQuery;
import io.trailkeep.casefile.CaseListService;
import io.trailkeep.casefile.CasePriority;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.CaseService.Choice;
import io.trailkeep.casefile.CaseSort;
import io.trailkeep.casefile.SlaState;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/**
 * Fills the model of the cases list (BUILD_PLAN §4.4) for {@link CaseListController} and for {@link
 * SavedViewController}, which shows the page again with the "Save view" dialog open when a save is refused, and
 * {@link CaseExportController}, which shows it with the reason when an export would be too large.
 */
@Component
class CaseListPage {

    /** The sort columns by name, so the template names each header's column without a static call. */
    private static final Map<String, CaseSort> COLUMNS =
            Arrays.stream(CaseSort.values()).collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));

    /** The statuses offered to someone who may not list archived cases (WP-24). */
    private static final CaseStatus[] WORKING = {CaseStatus.OPEN, CaseStatus.PENDING_CLOSURE, CaseStatus.CLOSED};

    /** The message key prefix that names a chip's value, per criterion whose values are enum constants. */
    private static final Map<String, String> VALUE_KEYS = Map.of(
            "status", "tk.case.status.",
            "sla", "tk.list.sla.",
            "priority", "tk.new.priority.",
            "sensitivity", "tk.list.sensitivity.");

    private final CaseListService cases;
    private final CaseExportService exports;

    CaseListPage(CaseListService cases, CaseExportService exports) {
        this.cases = cases;
        this.exports = exports;
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

    /**
     * Reads the list through the actor's scope and fills the model for the page or its htmx fragment, with both
     * dialogs closed.
     *
     * @param actor the actor
     * @param query the query
     * @param model the model
     */
    void fill(Actor actor, CaseListQuery query, Model model) {
        CaseList list = cases.list(actor, query);
        model.addAttribute("query", query);
        model.addAttribute("list", list);
        model.addAttribute("chips", chips(query, list));
        model.addAttribute("columns", COLUMNS);
        model.addAttribute("statuses", list.offersArchived() ? CaseStatus.values() : WORKING);
        model.addAttribute("slaStates", SlaState.values());
        model.addAttribute("priorities", CasePriority.values());
        model.addAttribute("sensitivities", Sensitivity.values());
        model.addAttribute("maxText", CaseFilter.MAX_TEXT);
        model.addAttribute("filtersOpen", false);
        model.addAttribute("saveOpen", false);
        model.addAttribute("offersDetails", exports.offersDetails(actor.permissions()));
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
