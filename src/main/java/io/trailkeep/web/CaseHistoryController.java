// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseHistoryService;
import io.trailkeep.casefile.HistoryKind;
import io.trailkeep.casefile.HistoryQuery;
import io.trailkeep.security.TrailkeepOidcUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The History page of a case (BUILD_PLAN §4.3): {@code GET /cases/{number}/history}, a plain page with a native
 * select for the kind of event and plain previous and next links, so it needs no script. The kind and the page are
 * read by {@link HistoryQuery#parse} (a kind no form offers, or a page that is not a positive number, is 422), then
 * {@link CaseHistoryService} decides: 404 for a case the person may not read, and for a restricted one it records
 * the read.
 */
@Controller
class CaseHistoryController {

    private final CaseHistoryService history;
    private final CasePage page;

    CaseHistoryController(CaseHistoryService history, CasePage page) {
        this.history = history;
        this.page = page;
    }

    @GetMapping("/cases/{number}/history")
    String history(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String number,
            @RequestParam(name = "kind", defaultValue = "all") String kind,
            @RequestParam(name = "page", defaultValue = "1") String pageNumber,
            Model model) {
        HistoryQuery query = HistoryQuery.parse(kind, pageNumber);
        model.addAttribute("h", history.history(page.actor(principal), number, query));
        model.addAttribute("kinds", HistoryKind.values());
        return "casefile/history";
    }
}
