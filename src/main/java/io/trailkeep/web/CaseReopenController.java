// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseException;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.closure.ClosureDecisionService;
import io.trailkeep.closure.ClosureException;
import io.trailkeep.security.CaseAction;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.DomainException.Kind;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Reopen (BUILD_PLAN §4.3 "Reopen (Manager+) → reason* → Open; SLA restarts from reopen date", WP-19 part c3). A
 * closed case's page offers it in a native dialog; {@code GET /cases/{n}/reopen} is the same form as a page, which is
 * how it works without JavaScript and what a refused post re-renders: 422 with the reason marked and summarized, 409
 * with Reload when the case moved on meanwhile. {@link CaseService#actionable} decides 404 and 403 ({@code REOPEN}:
 * Managers and Compliance Admins, closed cases only) before any input is read.
 */
@Controller
class CaseReopenController {

    private final CaseService cases;
    private final ClosureDecisionService decisions;
    private final CasePage page;

    CaseReopenController(CaseService cases, ClosureDecisionService decisions, CasePage page) {
        this.cases = cases;
        this.decisions = decisions;
        this.page = page;
    }

    @GetMapping("/cases/{number}/reopen")
    String form(@AuthenticationPrincipal TrailkeepOidcUser principal, @PathVariable String number, Model model) {
        cases.actionable(page.actor(principal), number, CaseAction.REOPEN);
        return formPage(number, "", false, false, model);
    }

    @PostMapping("/cases/{number}/reopen")
    String reopen(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String number,
            @RequestParam(defaultValue = "") String reason,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        Actor actor = page.actor(principal);
        cases.actionable(actor, number, CaseAction.REOPEN);
        int length = reason.replace("\r\n", "\n").strip().length();
        if (length == 0 || length > ClosureDecisionService.MAX_REASON) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            return formPage(number, reason, true, false, model);
        }
        try {
            decisions.reopen(actor, number, reason);
        } catch (ClosureException | CaseException refused) {
            if (refused.kind() != Kind.CONFLICT) {
                throw refused;
            }
            response.setStatus(HttpStatus.CONFLICT.value());
            return formPage(number, reason, false, true, model);
        }
        CaseNumber parsed = CaseNumber.parse(number);
        CaseNotice.REOPENED.leaveFor(request, parsed);
        return "redirect:/cases/" + parsed;
    }

    private static String formPage(String number, String reason, boolean invalid, boolean conflict, Model model) {
        model.addAttribute("number", number);
        model.addAttribute("reason", reason);
        model.addAttribute("reopenInvalid", invalid);
        model.addAttribute("conflict", conflict);
        return "casefile/reopen";
    }
}
