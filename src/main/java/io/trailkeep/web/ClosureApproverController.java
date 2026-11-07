// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseException;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.closure.ClosureDecisionService;
import io.trailkeep.closure.ClosureException;
import io.trailkeep.closure.ClosureService;
import io.trailkeep.closure.ClosureService.Approver;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.DomainException.Kind;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Reassign approver (BUILD_PLAN §4.3 "Reassign approver [Manager+, while pending]", WP-19 part c2b). The pending
 * banner offers it in a native dialog; {@code GET /cases/{n}/closure/{id}/approver} is the same form as a page, which
 * is how it works without JavaScript and what a refused post re-renders: 422 with the approver marked and summarized,
 * 409 with Reload when the request was decided or reassigned meanwhile. {@link ClosureService#reassignChoices} decides
 * 404 and 403 first; the approver id is parsed leniently, so a malformed one is a 422 after those checks.
 */
@Controller
class ClosureApproverController {

    private final ClosureService closures;
    private final ClosureDecisionService decisions;
    private final CasePage page;

    ClosureApproverController(ClosureService closures, ClosureDecisionService decisions, CasePage page) {
        this.closures = closures;
        this.decisions = decisions;
        this.page = page;
    }

    @GetMapping("/cases/{number}/closure/{id}/approver")
    String form(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String number,
            @PathVariable String id,
            Model model) {
        long requestId = id(id);
        List<Approver> approvers = closures.reassignChoices(page.actor(principal), number, requestId);
        return formPage(number, requestId, approvers, -1, false, false, model);
    }

    @PostMapping("/cases/{number}/closure/{id}/approver")
    String reassign(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String number,
            @PathVariable String id,
            @RequestParam(required = false) @Nullable String approverId,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        Actor actor = page.actor(principal);
        long requestId = id(id);
        List<Approver> approvers = closures.reassignChoices(actor, number, requestId);
        long chosen = approverId == null ? -1 : id(approverId);
        if (approvers.stream().map(Approver::id).noneMatch(a -> a == chosen)) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            return formPage(number, requestId, approvers, chosen, true, false, model);
        }
        try {
            decisions.reassignApprover(actor, number, requestId, chosen);
        } catch (ClosureException | CaseException refused) {
            boolean invalid = refused.kind() == Kind.INVALID;
            if (!invalid && refused.kind() != Kind.CONFLICT) {
                throw refused;
            }
            // INVALID: the rules changed between the form and the post (the chosen Manager left the team, say).
            response.setStatus(invalid ? HttpStatus.UNPROCESSABLE_CONTENT.value() : HttpStatus.CONFLICT.value());
            return formPage(number, requestId, approvers, chosen, invalid, !invalid, model);
        }
        CaseNumber parsed = CaseNumber.parse(number);
        CaseNotice.APPROVER_REASSIGNED.leaveFor(request, parsed);
        return "redirect:/cases/" + parsed;
    }

    private static String formPage(
            String number,
            long requestId,
            List<Approver> approvers,
            long chosen,
            boolean invalid,
            boolean conflict,
            Model model) {
        model.addAttribute("number", number);
        model.addAttribute("requestId", requestId);
        model.addAttribute("approvers", approvers);
        model.addAttribute("chosen", chosen);
        model.addAttribute("approverErrors", invalid ? List.of("approverId") : List.of());
        model.addAttribute("conflict", conflict);
        return "casefile/reassign-approver";
    }

    private static long id(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            return -1;
        }
    }
}
