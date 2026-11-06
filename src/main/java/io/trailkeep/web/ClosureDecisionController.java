// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseException;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.closure.ClosureDecision;
import io.trailkeep.closure.ClosureDecisionService;
import io.trailkeep.closure.ClosureException;
import io.trailkeep.closure.ClosureService;
import io.trailkeep.closure.ClosureService.PendingClosure;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.DomainException.Kind;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Deciding a closure request (BUILD_PLAN §4.3, WP-19 part c2). The case page offers Approve as a one-button form and
 * Reject or request changes in a native dialog; {@code GET /cases/{n}/closure/{id}/decision} is that form as a page,
 * which is how it works without JavaScript and what a refused post re-renders: 422 with each wrong field marked and
 * summarized, 409 with Reload when a concurrent decision got there first. {@link ClosureService#decidable} decides 404
 * and 403 first (an unreadable case, a request that is not the case's pending one, a denied action or someone other
 * than the approver); parameters are read leniently, so a malformed one is a 422 after those checks, never a 400.
 */
@Controller
class ClosureDecisionController {

    private final ClosureService closures;
    private final ClosureDecisionService decisions;
    private final CasePage page;

    ClosureDecisionController(ClosureService closures, ClosureDecisionService decisions, CasePage page) {
        this.closures = closures;
        this.decisions = decisions;
        this.page = page;
    }

    /**
     * What the Reject or request changes form holds.
     *
     * @param decision the decision chosen, or empty
     * @param reason the reason as typed
     */
    record DecisionForm(String decision, String reason) {

        /** An empty form. */
        static final DecisionForm EMPTY = new DecisionForm("", "");
    }

    @GetMapping("/cases/{number}/closure/{id}/decision")
    String form(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String number,
            @PathVariable String id,
            Model model) {
        PendingClosure pending = closures.decidable(page.actor(principal), number, id(id));
        return formPage(number, pending, DecisionForm.EMPTY, List.of(), false, model);
    }

    @PostMapping("/cases/{number}/closure/{id}/decision")
    String decide(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String number,
            @PathVariable String id,
            @RequestParam(defaultValue = "") String decision,
            @RequestParam(defaultValue = "") String reason,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        Actor actor = page.actor(principal);
        PendingClosure pending = closures.decidable(actor, number, id(id));
        DecisionForm form = new DecisionForm(decision, reason);
        ClosureDecision chosen = decision(decision);
        List<String> errors = new ArrayList<>(2);
        if (chosen == ClosureDecision.PENDING) {
            errors.add("decision");
        }
        int length = reason.replace("\r\n", "\n").strip().length();
        if (length > ClosureDecisionService.MAX_REASON || (length == 0 && chosen != ClosureDecision.APPROVED)) {
            errors.add("reason");
        }
        if (!errors.isEmpty()) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            return formPage(number, pending, form, errors, false, model);
        }
        try {
            decisions.decide(actor, number, pending.id(), chosen, reason.isBlank() ? null : reason);
        } catch (ClosureException | CaseException refused) {
            if (refused.kind() != Kind.CONFLICT) {
                throw refused;
            }
            response.setStatus(HttpStatus.CONFLICT.value());
            return formPage(number, pending, form, List.of(), true, model);
        }
        CaseNumber parsed = CaseNumber.parse(number);
        CaseNotice.valueOf("CLOSURE_" + chosen.name()).leaveFor(request, parsed);
        return "redirect:/cases/" + parsed;
    }

    private static String formPage(
            String number,
            PendingClosure pending,
            DecisionForm form,
            List<String> errors,
            boolean conflict,
            Model model) {
        model.addAttribute("number", number);
        model.addAttribute("pending", pending);
        model.addAttribute("decisionForm", form);
        model.addAttribute("decisionErrors", errors);
        model.addAttribute("conflict", conflict);
        return "casefile/closure-decision";
    }

    /** The decision posted, or {@code PENDING} for anything that is not one. */
    private static ClosureDecision decision(String value) {
        for (ClosureDecision candidate : ClosureDecision.values()) {
            if (candidate.name().equals(value)) {
                return candidate;
            }
        }
        return ClosureDecision.PENDING;
    }

    private static long id(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            return -1;
        }
    }
}
