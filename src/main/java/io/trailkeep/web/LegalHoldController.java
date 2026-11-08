// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseException;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.LegalHoldService;
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
 * Legal hold (BUILD_PLAN §4.6, WP-24 part b): the case page offers Compliance Admins "Place legal hold" or "Lift legal
 * hold" in a native dialog; {@code GET /cases/{n}/legal-hold} is the same form as a page, which is how it works without
 * JavaScript and what a refused post re-renders: 422 with the reason marked and summarized, 409 with Reload when
 * someone else placed or lifted the hold meanwhile. The form says which change it asks for ({@code hold}), so a stale
 * form never toggles the other way. {@link CaseService#actionable} decides 404 and 403 ({@code LEGAL_HOLD}) before any
 * input is read.
 */
@Controller
class LegalHoldController {

    private final CaseService cases;
    private final LegalHoldService holds;
    private final CasePage page;

    LegalHoldController(CaseService cases, LegalHoldService holds, CasePage page) {
        this.cases = cases;
        this.holds = holds;
        this.page = page;
    }

    @GetMapping("/cases/{number}/legal-hold")
    String form(@AuthenticationPrincipal TrailkeepOidcUser principal, @PathVariable String number, Model model) {
        Actor actor = page.actor(principal);
        cases.actionable(actor, number, CaseAction.LEGAL_HOLD);
        return formPage(number, holds.current(actor, number).isEmpty(), "", false, false, model);
    }

    @PostMapping("/cases/{number}/legal-hold")
    String change(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String number,
            @RequestParam(defaultValue = "false") boolean hold,
            @RequestParam(defaultValue = "") String reason,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        Actor actor = page.actor(principal);
        cases.actionable(actor, number, CaseAction.LEGAL_HOLD);
        try {
            holds.change(actor, number, hold, reason);
        } catch (CaseException refused) {
            if (refused.kind() == Kind.INVALID) {
                response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
                return formPage(number, hold, reason, true, false, model);
            }
            if (refused.kind() == Kind.CONFLICT) {
                response.setStatus(HttpStatus.CONFLICT.value());
                return formPage(number, hold, reason, false, true, model);
            }
            throw refused;
        }
        CaseNumber parsed = CaseNumber.parse(number);
        (hold ? CaseNotice.HOLD_PLACED : CaseNotice.HOLD_LIFTED).leaveFor(request, parsed);
        return "redirect:/cases/" + parsed;
    }

    private static String formPage(
            String number, boolean hold, String reason, boolean invalid, boolean conflict, Model model) {
        model.addAttribute("number", number);
        model.addAttribute("hold", hold);
        model.addAttribute("reason", reason);
        model.addAttribute("holdInvalid", invalid);
        model.addAttribute("conflict", conflict);
        return "casefile/legal-hold";
    }
}
