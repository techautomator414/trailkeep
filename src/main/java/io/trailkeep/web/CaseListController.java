// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseFilter;
import io.trailkeep.casefile.CaseListQuery;
import io.trailkeep.casefile.CaseListService;
import io.trailkeep.casefile.CaseSort;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.CaseStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
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
 * are counted and listed is decided by {@link CaseListService} through the person's scope. An htmx request (a sort
 * header, a page link or the filter form) gets only the results fragment; app.js then moves focus to the control that
 * asked for it, found by the {@code HX-Trigger} id, or else to the status line, and announces the count.
 */
@Controller
class CaseListController {

    /** The ids the results fragment gives its sort headers and page links. */
    private static final Pattern TRIGGER = Pattern.compile("tk-(sort-[a-z]+|page-(previous|next))");

    /** The sort columns by name, so the template names each header's column without a static call. */
    private static final Map<String, CaseSort> COLUMNS =
            Arrays.stream(CaseSort.values()).collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));

    private final CaseListService cases;
    private final CasePage page;

    CaseListController(CaseListService cases, CasePage page) {
        this.cases = cases;
        this.page = page;
    }

    @GetMapping("/cases")
    String list(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @RequestParam MultiValueMap<String, String> parameters,
            HttpServletRequest request,
            Model model) {
        CaseListQuery query = CaseListQuery.parse(parameters);
        model.addAttribute("query", query);
        model.addAttribute("list", cases.list(page.actor(principal), query));
        model.addAttribute("columns", COLUMNS);
        model.addAttribute("statuses", CaseStatus.values());
        model.addAttribute("maxText", CaseFilter.MAX_TEXT);
        if (!"true".equals(request.getHeader("HX-Request"))) {
            return "casefile/cases";
        }
        String trigger = request.getHeader("HX-Trigger");
        model.addAttribute("focus", trigger != null && TRIGGER.matcher(trigger).matches() ? trigger : "");
        return "casefile/fragments/case-results :: results";
    }
}
