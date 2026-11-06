// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.security.CaseAction;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.updaterequest.UpdateRequestException;
import io.trailkeep.updaterequest.UpdateRequestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 * Request Update and Mark answered (BUILD_PLAN §4.3, WP-18). The case page offers the request in a native dialog;
 * {@code GET /cases/{n}/request-update} is the same form as a page, which is how it works without JavaScript, and
 * the page a refused request re-renders: 422 for a recipient who cannot read the case or a bad message, 409 for a
 * second open request to the same person. {@link UpdateRequestService} decides 404 and 403 first. Parameters are
 * parsed leniently, so a malformed one is a 422 or a 404 after those checks, never a 400 before them.
 */
@Controller
class UpdateRequestController {

    private final UpdateRequestService requests;
    private final CaseService cases;
    private final CasePage page;

    UpdateRequestController(UpdateRequestService requests, CaseService cases, CasePage page) {
        this.requests = requests;
        this.cases = cases;
        this.page = page;
    }

    @GetMapping("/cases/{number}/request-update")
    String form(@AuthenticationPrincipal TrailkeepOidcUser principal, @PathVariable String number, Model model) {
        return formPage(page.actor(principal), number, -1, "", null, model);
    }

    @PostMapping("/cases/{number}/request-update")
    String request(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String number,
            @RequestParam(required = false) @Nullable String recipientId,
            @RequestParam(defaultValue = "") String message,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        Actor actor = page.actor(principal);
        long recipient = id(recipientId);
        try {
            requests.request(actor, number, recipient, message);
        } catch (UpdateRequestException refused) {
            if (refused.kind() != Kind.INVALID && refused.kind() != Kind.CONFLICT) {
                throw refused;
            }
            boolean conflict = refused.kind() == Kind.CONFLICT;
            response.setStatus(conflict ? HttpStatus.CONFLICT.value() : HttpStatus.UNPROCESSABLE_CONTENT.value());
            return formPage(actor, number, recipient, message, refused.kind(), model);
        }
        CaseNumber parsed = CaseNumber.parse(number);
        CaseNotice.UPDATE_REQUESTED.leaveFor(request, parsed);
        return "redirect:/cases/" + parsed;
    }

    @PostMapping("/cases/{number}/update-requests/{id}/answered")
    String answered(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String number,
            @PathVariable String id,
            HttpServletRequest request) {
        requests.markAnswered(page.actor(principal), number, id(id));
        CaseNumber parsed = CaseNumber.parse(number);
        CaseNotice.UPDATE_ANSWERED.leaveFor(request, parsed);
        return "redirect:/cases/" + parsed;
    }

    private String formPage(
            Actor actor, String number, long selected, String message, @Nullable Kind problem, Model model) {
        model.addAttribute("ref", cases.actionable(actor, number, CaseAction.REQUEST_UPDATE));
        model.addAttribute("recipients", requests.recipients(actor, number));
        model.addAttribute("selected", selected);
        model.addAttribute("message", message);
        model.addAttribute("problem", problem == null ? null : problem.name());
        return "casefile/request-update";
    }

    /** An id from the request; anything unreadable matches nobody (422) and no request (404). */
    private static long id(@Nullable String value) {
        try {
            return value == null ? -1 : Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            return -1;
        }
    }
}
