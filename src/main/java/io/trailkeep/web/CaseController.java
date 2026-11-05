// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseFields;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.CaseView;
import io.trailkeep.casefile.TitleWarning;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.UserDirectoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.InstantSource;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The one-page create form (BUILD_PLAN §4.2) and the case page (§4.3), which shows a one-shot {@link CaseNotice}. The
 * case number is the only identifier in a URL; a case the person may not read answers 404 like an absent one, decided
 * by {@link CaseService} through the person's scope, which also decides who may create. The form is re-rendered with
 * 422 when a field is invalid or the title trips the PHI heuristic and has not been confirmed.
 */
@Controller
class CaseController {

    private static final String FORM = "casefile/new-case";

    /** The form's fields in page order, so the error summary follows the page. */
    private static final List<String> FIELDS = List.of(
            "caseTypeId",
            "title",
            "priority",
            "identifiedOn",
            "teamId",
            "ownerId",
            "subjectText",
            "description",
            "sensitivity");

    private final CaseService cases;
    private final UserDirectoryService directory;
    private final InstantSource clock;
    private final AppProperties app;

    CaseController(CaseService cases, UserDirectoryService directory, InstantSource clock, AppProperties app) {
        this.cases = cases;
        this.directory = directory;
        this.clock = clock;
        this.app = app;
    }

    @GetMapping("/cases/new")
    String newCase(@AuthenticationPrincipal TrailkeepOidcUser principal, Model model) {
        Map<String, String> values = new HashMap<>();
        values.put("identifiedOn", today().toString());
        values.put("ownerId", Long.toString(principal.userId()));
        values.put("priority", "NORMAL");
        values.put("sensitivity", "STANDARD");
        return form(actor(principal), values, List.of(), model);
    }

    @PostMapping("/cases")
    String create(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @Valid CaseFields fields,
            BindingResult binding,
            @RequestParam(defaultValue = "false") boolean titleConfirmed,
            @RequestParam Map<String, String> values,
            HttpServletResponse response,
            Model model) {
        Actor actor = actor(principal);
        if (binding.hasErrors()) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            return form(
                    actor,
                    values,
                    FIELDS.stream().filter(binding::hasFieldErrors).toList(),
                    model);
        }
        Set<TitleWarning> warnings = cases.titleWarnings(fields.title());
        if (!warnings.isEmpty() && !titleConfirmed) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("warnings", warnings);
            return form(actor, values, List.of(), model);
        }
        // A query flag, not a flash attribute: flash maps do not survive the JSON session format (ADR-0008).
        return "redirect:/cases/" + cases.create(actor, fields) + "?created";
    }

    @GetMapping("/cases/{number}")
    String casePage(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String number,
            @RequestParam(required = false) @Nullable String created,
            HttpServletRequest request,
            Model model) {
        CaseView view = cases.view(actor(principal), number);
        model.addAttribute("c", view);
        model.addAttribute("created", created != null);
        model.addAttribute("notice", CaseNotice.take(request, view.number()));
        return "casefile/case";
    }

    private String form(Actor actor, Map<String, String> values, List<String> errors, Model model) {
        model.addAttribute("choices", cases.createChoices(actor));
        model.addAttribute("values", values);
        model.addAttribute("errors", errors);
        model.addAttribute("today", today());
        return FORM;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), app.timezone());
    }

    private Actor actor(TrailkeepOidcUser principal) {
        return new Actor(directory.permissionsOf(principal.userId()), principal.displayName());
    }
}
