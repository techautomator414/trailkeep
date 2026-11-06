// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseException;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.closure.ClosureException;
import io.trailkeep.closure.ClosureService;
import io.trailkeep.closure.ClosureService.Approver;
import io.trailkeep.closure.ClosureService.ClosureChoices;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.DomainException.Kind;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
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
 * Request Closure (BUILD_PLAN §4.3, WP-19 part c1). The case page offers it in a native dialog; {@code GET
 * /cases/{n}/request-closure} is the same form as a page, which is how it works without JavaScript, and the page a
 * refused request re-renders: 422 with each wrong field marked and summarized (outcome, summary, follow-ups,
 * approver), 409 with Reload when the case moved on. {@link ClosureService} decides 404 and 403 first; parameters are
 * read leniently, so a malformed one is a 422 after those checks, never a 400 before them.
 */
@Controller
class ClosureController {

    private static final int MAX_TEXT = 5000;
    private static final String PAGE = "casefile/request-closure";

    private final ClosureService closures;
    private final CasePage page;

    ClosureController(ClosureService closures, CasePage page) {
        this.closures = closures;
        this.page = page;
    }

    /**
     * What the form holds.
     *
     * @param outcome the outcome chosen, or empty
     * @param summary the resolution summary as typed
     * @param followUps the follow-up actions as typed
     * @param approverId the approver chosen, or {@code -1}
     */
    record ClosureForm(String outcome, String summary, String followUps, long approverId) {

        /** An empty form. */
        static final ClosureForm EMPTY = new ClosureForm("", "", "", -1);
    }

    @GetMapping("/cases/{number}/request-closure")
    String form(@AuthenticationPrincipal TrailkeepOidcUser principal, @PathVariable String number, Model model) {
        Actor actor = page.actor(principal);
        return formPage(number, closures.choices(actor, number), ClosureForm.EMPTY, List.of(), false, model);
    }

    @PostMapping("/cases/{number}/request-closure")
    String request(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String number,
            @RequestParam(defaultValue = "") String outcome,
            @RequestParam(defaultValue = "") String summary,
            @RequestParam(defaultValue = "") String followUps,
            @RequestParam(required = false) @Nullable String approverId,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        Actor actor = page.actor(principal);
        ClosureChoices choices = closures.choices(actor, number);
        ClosureForm form = new ClosureForm(outcome, summary, followUps, id(approverId));
        List<String> errors = errors(choices, form);
        if (!errors.isEmpty()) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            return formPage(number, choices, form, errors, false, model);
        }
        try {
            closures.requestClosure(
                    actor, number, outcome, summary, followUps.isBlank() ? null : followUps, form.approverId());
        } catch (ClosureException | CaseException refused) {
            if (refused.kind() == Kind.CONFLICT) {
                response.setStatus(HttpStatus.CONFLICT.value());
                return formPage(number, choices, form, List.of(), true, model);
            }
            if (refused.kind() != Kind.INVALID) {
                throw refused;
            }
            // The approver list changed between the form and the request (someone left the team, say).
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            return formPage(number, choices, form, List.of("approverId"), false, model);
        }
        CaseNumber parsed = CaseNumber.parse(number);
        CaseNotice.CLOSURE_REQUESTED.leaveFor(request, parsed);
        return "redirect:/cases/" + parsed;
    }

    private static String formPage(
            String number,
            ClosureChoices choices,
            ClosureForm form,
            List<String> errors,
            boolean conflict,
            Model model) {
        model.addAttribute("number", number);
        model.addAttribute("choices", choices);
        model.addAttribute("form", form);
        model.addAttribute("closureErrors", errors);
        model.addAttribute("conflict", conflict);
        return PAGE;
    }

    /** The fields to mark, in page order. */
    private static List<String> errors(ClosureChoices choices, ClosureForm form) {
        List<String> errors = new ArrayList<>(4);
        if (!choices.outcomes().contains(form.outcome())) {
            errors.add("outcome");
        }
        if (form.summary().isBlank() || length(form.summary()) > MAX_TEXT) {
            errors.add("summary");
        }
        if (length(form.followUps()) > MAX_TEXT) {
            errors.add("followUps");
        }
        if (choices.approvers().stream().map(Approver::id).noneMatch(id -> id == form.approverId())) {
            errors.add("approverId");
        }
        return errors;
    }

    /** As the browser's maxlength counts it, and the service: a line break is one character. */
    private static int length(String text) {
        return text.replace("\r\n", "\n").length();
    }

    private static long id(@Nullable String value) {
        try {
            return value == null ? -1 : Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            return -1;
        }
    }
}
