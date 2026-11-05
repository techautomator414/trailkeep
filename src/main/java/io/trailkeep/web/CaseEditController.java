// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseChanges;
import io.trailkeep.casefile.CaseException;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.EditableCase;
import io.trailkeep.casefile.TitleWarning;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.user.UserDirectoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * Edit (BUILD_PLAN §4.3). {@link CaseService#editable} runs first on every request, so a case the person may not
 * read answers 404 and one they may not edit 403 before anything about their input is said. Invalid fields, or a
 * changed title that trips the PHI heuristic unconfirmed, re-render the form with 422; a stale version re-renders it
 * with 409, the person's input kept and Reload offered instead of Save.
 */
@Controller
class CaseEditController {

    private static final String FORM = "casefile/edit-case";

    /** The form's fields in page order, so the error summary follows the page. */
    private static final List<String> FIELDS = List.of("title", "priority", "subjectText", "description");

    private final CaseService cases;
    private final UserDirectoryService directory;

    CaseEditController(CaseService cases, UserDirectoryService directory) {
        this.cases = cases;
        this.directory = directory;
    }

    @GetMapping("/cases/{number}/edit")
    String editForm(@AuthenticationPrincipal TrailkeepOidcUser principal, @PathVariable String number, Model model) {
        EditableCase edit = cases.editable(actor(principal), number);
        CaseChanges current = edit.current();
        Map<String, String> values = new HashMap<>();
        values.put("title", current.title());
        values.put("priority", current.priority().name());
        values.put("subjectText", Objects.toString(current.subjectText(), ""));
        values.put("description", current.description());
        values.put("version", Integer.toString(edit.version()));
        return form(edit, values, List.of(), false, model);
    }

    @PostMapping("/cases/{number}/edit")
    String save(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String number,
            @Valid CaseChanges changes,
            BindingResult binding,
            @RequestParam(defaultValue = "false") boolean titleConfirmed,
            @RequestParam Map<String, String> values,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        Actor actor = actor(principal);
        EditableCase edit = cases.editable(actor, number);
        if (binding.hasErrors()) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            return form(
                    edit,
                    values,
                    FIELDS.stream().filter(binding::hasFieldErrors).toList(),
                    false,
                    model);
        }
        Set<TitleWarning> warnings =
                changes.title().strip().equals(edit.current().title())
                        ? Set.of()
                        : cases.titleWarnings(changes.title());
        if (!warnings.isEmpty() && !titleConfirmed) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("warnings", warnings);
            return form(edit, values, List.of(), false, model);
        }
        try {
            cases.edit(actor, number, version(values.get("version")), changes);
        } catch (CaseException refused) {
            if (refused.kind() != Kind.CONFLICT) {
                throw refused;
            }
            response.setStatus(HttpStatus.CONFLICT.value());
            return form(cases.editable(actor, number), values, List.of(), true, model);
        }
        CaseNotice.SAVED.leaveFor(request, edit.number());
        return "redirect:/cases/" + edit.number();
    }

    private static String form(
            EditableCase edit, Map<String, String> values, List<String> errors, boolean conflict, Model model) {
        model.addAttribute("edit", edit);
        model.addAttribute("values", values);
        model.addAttribute("errors", errors);
        model.addAttribute("conflict", conflict);
        return FORM;
    }

    /** The version the form was loaded with; anything unreadable can only be stale. */
    private static int version(@Nullable String version) {
        try {
            return Integer.parseInt(String.valueOf(version));
        } catch (NumberFormatException unreadable) {
            return -1;
        }
    }

    private Actor actor(TrailkeepOidcUser principal) {
        return new Actor(directory.permissionsOf(principal.userId()), principal.displayName());
    }
}
