// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseConfigException;
import io.trailkeep.casefile.CaseConfigService;
import io.trailkeep.casefile.CaseConfigService.Actor;
import io.trailkeep.casefile.CaseTypeCommand;
import io.trailkeep.casefile.EscalateTo;
import io.trailkeep.casefile.SlaAnchor;
import io.trailkeep.casefile.SlaPolicyCommand;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.user.UserDirectoryService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * SLA policy and case type administration (BUILD_PLAN §4.6). Authorization is the service's: it rebuilds the
 * actor's permissions from the database on every request and refuses anyone but a Compliance Admin. Its refusals
 * are {@code DomainException}s, rendered by the shell's {@code ErrorAdvice}.
 */
@Controller
@RequestMapping("/admin")
class CaseConfigController {

    /**
     * The case type form: outcomes are typed one per line.
     *
     * @param name the name
     * @param slaPolicyId the policy
     * @param retentionYears retention after closure
     * @param active whether new cases may use the type
     * @param outcomes the outcomes, one per line
     */
    record CaseTypeForm(
            String name,
            long slaPolicyId,
            int retentionYears,
            boolean active,
            @Nullable String outcomes) {
        CaseTypeCommand toCommand() {
            List<String> lines = Arrays.stream((outcomes == null ? "" : outcomes).split("\\R"))
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .toList();
            return new CaseTypeCommand(name, slaPolicyId, retentionYears, active, lines);
        }
    }

    private static final String POLICIES = "casefile/sla-policies";
    private static final String POLICY = "casefile/sla-policy";
    private static final String TYPES = "casefile/case-types";
    private static final String TYPE = "casefile/case-type";

    private final CaseConfigService config;
    private final UserDirectoryService directory;
    private final Validator validator;

    CaseConfigController(CaseConfigService config, UserDirectoryService directory, Validator validator) {
        this.config = config;
        this.directory = directory;
        this.validator = validator;
    }

    @GetMapping("/sla-policies")
    String policies(@AuthenticationPrincipal TrailkeepOidcUser principal, Model model) {
        model.addAttribute("policies", config.policies(actor(principal)));
        return policyChoices(model, POLICIES);
    }

    @GetMapping("/sla-policies/{id}")
    String policy(@AuthenticationPrincipal TrailkeepOidcUser principal, @PathVariable long id, Model model) {
        model.addAttribute("policy", config.policy(actor(principal), id));
        return policyChoices(model, POLICY);
    }

    @PostMapping({"/sla-policies", "/sla-policies/{id}"})
    String savePolicy(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable(required = false) @Nullable Long id,
            @Valid SlaPolicyCommand command,
            BindingResult binding,
            Model model,
            HttpServletResponse response) {
        if (binding.hasErrors()) {
            return invalid(response, model, id == null ? policies(principal, model) : policy(principal, id, model));
        }
        try {
            return "redirect:/admin/sla-policies/" + config.savePolicy(actor(principal), id, command);
        } catch (CaseConfigException refused) {
            if (refused.kind() != Kind.CONFLICT) {
                throw refused;
            }
            return nameTaken(
                    response,
                    model,
                    command.name(),
                    id == null ? policies(principal, model) : policy(principal, id, model));
        }
    }

    @GetMapping("/case-types")
    String caseTypes(@AuthenticationPrincipal TrailkeepOidcUser principal, Model model) {
        Actor actor = actor(principal);
        model.addAttribute("caseTypes", config.caseTypes(actor));
        model.addAttribute("policies", config.policies(actor));
        return TYPES;
    }

    @GetMapping("/case-types/{id}")
    String caseType(@AuthenticationPrincipal TrailkeepOidcUser principal, @PathVariable long id, Model model) {
        Actor actor = actor(principal);
        model.addAttribute("caseType", config.caseType(actor, id));
        model.addAttribute("policies", config.policies(actor));
        return TYPE;
    }

    @PostMapping({"/case-types", "/case-types/{id}"})
    String saveCaseType(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable(required = false) @Nullable Long id,
            CaseTypeForm form,
            BindingResult binding,
            Model model,
            HttpServletResponse response) {
        CaseTypeCommand command = binding.hasErrors() ? null : form.toCommand();
        if (command == null || !validator.validate(command).isEmpty()) {
            return invalid(response, model, id == null ? caseTypes(principal, model) : caseType(principal, id, model));
        }
        try {
            return "redirect:/admin/case-types/" + config.saveCaseType(actor(principal), id, command);
        } catch (CaseConfigException refused) {
            if (refused.kind() != Kind.CONFLICT) {
                throw refused;
            }
            return nameTaken(
                    response,
                    model,
                    command.name(),
                    id == null ? caseTypes(principal, model) : caseType(principal, id, model));
        }
    }

    private Actor actor(TrailkeepOidcUser principal) {
        return new Actor(directory.permissionsOf(principal.userId()), principal.displayName());
    }

    private static String policyChoices(Model model, String view) {
        model.addAttribute("anchors", SlaAnchor.values());
        model.addAttribute("escalations", EscalateTo.values());
        return view;
    }

    /** A taken name is a field error on {@code name}: the form is shown again with what was typed. */
    private static String nameTaken(HttpServletResponse response, Model model, String typed, String view) {
        model.addAttribute("nameTaken", true);
        model.addAttribute("typedName", typed);
        return invalid(response, model, view);
    }

    private static String invalid(HttpServletResponse response, Model model, String view) {
        response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
        model.addAttribute("invalid", true);
        return view;
    }
}
