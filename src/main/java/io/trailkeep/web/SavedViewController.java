// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseListQuery;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.SavedViewService;
import io.trailkeep.casefile.SavedViewService.Created;
import io.trailkeep.casefile.SavedViewService.Refused;
import io.trailkeep.security.TrailkeepOidcUser;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Saved views (BUILD_PLAN §4.4 "Save View"). The cases page's "Save view" dialog posts the name and the list's query
 * string; the query is read back through {@link CaseListQuery#parse}, so a stored view holds only values a form
 * offers, and saved in its canonical form. A refused save (blank, too long or duplicate name, nothing to save) is a
 * 422 showing the cases page again with the dialog open and the reason. {@code /cases/views} lists the person's views
 * with a Delete button each; another person's view, or an unknown id, is 404, so a view's existence stays private.
 */
@Controller
class SavedViewController {

    private final SavedViewService views;
    private final CaseListPage list;
    private final CasePage page;

    SavedViewController(SavedViewService views, CaseListPage list, CasePage page) {
        this.views = views;
        this.list = list;
        this.page = page;
    }

    @PostMapping("/cases/views")
    String save(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @RequestParam(required = false) @Nullable String name,
            @RequestParam(required = false) @Nullable String query,
            HttpServletResponse response,
            Model model) {
        Actor actor = page.actor(principal);
        CaseListQuery parsed = CaseListQuery.parse(parameters(query));
        String typed = Objects.requireNonNullElse(name, "");
        return switch (views.save(actor, typed, parsed)) {
            case Created created -> "redirect:/cases?" + created.view().query();
            case Refused refused -> {
                response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
                list.fill(actor, parsed, model);
                model.addAttribute("saveOpen", true);
                model.addAttribute("saveProblem", refused.problem());
                model.addAttribute("viewName", typed);
                yield "casefile/cases";
            }
        };
    }

    @GetMapping("/cases/views")
    String manage(@AuthenticationPrincipal TrailkeepOidcUser principal, Model model) {
        model.addAttribute("views", views.views(principal.userId()));
        return "casefile/views";
    }

    @PostMapping("/cases/views/{id}/delete")
    String delete(@AuthenticationPrincipal TrailkeepOidcUser principal, @PathVariable String id) {
        views.delete(page.actor(principal), id(id));
        return "redirect:/cases/views";
    }

    /** The posted query string as parameters, decoded as a browser would. */
    private static Map<String, List<String>> parameters(@Nullable String query) {
        Map<String, List<String>> decoded = new HashMap<>();
        UriComponentsBuilder.newInstance()
                .query(query == null ? "" : query)
                .build()
                .getQueryParams()
                .forEach((key, values) -> decoded.put(
                        decode(key),
                        values.stream()
                                .map(value -> decode(value == null ? "" : value))
                                .toList()));
        return decoded;
    }

    /** Decodes one value; a malformed escape is kept as typed, so the parser refuses it rather than failing. */
    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return value;
        }
    }

    /** A view id from the path; anything unreadable matches no view and is 404 like another person's view. */
    private static long id(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            return -1;
        }
    }
}
