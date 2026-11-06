// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseListQuery;
import io.trailkeep.casefile.CaseListService;
import io.trailkeep.security.TrailkeepOidcUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The cases list (BUILD_PLAN §4.4): a GET with the filter, order and page in the query string, read by {@link
 * CaseListQuery#parse}, which answers 422 for a value no form offers (an unknown sort column, for one). Which cases
 * are counted and listed is decided by {@link CaseListService} through the person's scope. The filters and "Save
 * view" sit in native {@code <dialog>}s; {@code filters=1} and {@code save=1} render them open, which is how their
 * links work without JavaScript. An htmx request (a sort header, a page link, a filter chip or the filter form) gets
 * the results fragment and, out of band, fresh filter controls; app.js then moves focus to the control that asked
 * for it, found by the {@code HX-Trigger} id, or else to the status line, and announces the count.
 */
@Controller
class CaseListController {

    /** The ids the results fragment gives its sort headers and page links. */
    private static final Pattern TRIGGER = Pattern.compile("tk-(sort-[a-z]+|page-(previous|next))");

    private final CaseListPage list;
    private final CasePage page;

    CaseListController(CaseListPage list, CasePage page) {
        this.list = list;
        this.page = page;
    }

    @GetMapping("/cases")
    String list(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @RequestParam MultiValueMap<String, String> parameters,
            HttpServletRequest request,
            Model model) {
        CaseListQuery query = CaseListQuery.parse(parameters);
        list.fill(page.actor(principal), query, model);
        if (!"true".equals(request.getHeader("HX-Request"))) {
            model.addAttribute("filtersOpen", "1".equals(parameters.getFirst("filters")));
            model.addAttribute("saveOpen", "1".equals(parameters.getFirst("save")));
            return "casefile/cases";
        }
        String trigger = request.getHeader("HX-Trigger");
        model.addAttribute("focus", trigger != null && TRIGGER.matcher(trigger).matches() ? trigger : "");
        return "casefile/fragments/case-swap";
    }
}
