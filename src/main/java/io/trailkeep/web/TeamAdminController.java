// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.user.TeamAdminException;
import io.trailkeep.user.TeamAdminService;
import io.trailkeep.user.TeamAdminService.Actor;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserDirectoryService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Team and team-role administration (BUILD_PLAN §4.6). Authorization is the service's: it rebuilds the actor's
 * permissions from the database on every request and refuses anyone but a System Admin.
 */
@Controller
@RequestMapping("/admin/teams")
class TeamAdminController {

    /** Create or rename a team. */
    record TeamForm(@NotBlank @Size(max = 120) String name, boolean active) {}

    /** Grant or change a role; a missing value is answered 422 like any other invalid input. */
    record MemberForm(@Nullable Long userId, @Nullable TeamRole role) {}

    private static final String TEAMS = "user/teams";
    private static final String TEAM = "user/team";

    private final TeamAdminService admin;
    private final UserDirectoryService directory;

    TeamAdminController(TeamAdminService admin, UserDirectoryService directory) {
        this.admin = admin;
        this.directory = directory;
    }

    @GetMapping
    String teams(@AuthenticationPrincipal TrailkeepOidcUser principal, Model model) {
        model.addAttribute("teams", admin.teams(actor(principal)));
        return TEAMS;
    }

    @PostMapping
    String create(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @Valid TeamForm form,
            BindingResult binding,
            Model model,
            HttpServletResponse response) {
        if (binding.hasErrors()) {
            model.addAttribute("teams", admin.teams(actor(principal)));
            return invalid(model, response, TEAMS);
        }
        return "redirect:/admin/teams/" + admin.createTeam(actor(principal), form.name());
    }

    @GetMapping("/{teamId}")
    String team(@AuthenticationPrincipal TrailkeepOidcUser principal, @PathVariable long teamId, Model model) {
        Actor actor = actor(principal);
        model.addAttribute("team", admin.team(actor, teamId));
        model.addAttribute("members", admin.members(actor, teamId));
        model.addAttribute("users", admin.assignableUsers(actor));
        model.addAttribute("roles", TeamRole.values());
        return TEAM;
    }

    @PostMapping("/{teamId}")
    String update(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable long teamId,
            @Valid TeamForm form,
            BindingResult binding,
            Model model,
            HttpServletResponse response) {
        if (binding.hasErrors()) {
            return invalid(model, response, team(principal, teamId, model));
        }
        admin.updateTeam(actor(principal), teamId, form.name(), form.active());
        return "redirect:/admin/teams/" + teamId;
    }

    @PostMapping("/{teamId}/members")
    String assign(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable long teamId,
            @Valid MemberForm form,
            BindingResult binding,
            Model model,
            HttpServletResponse response) {
        Long userId = form.userId();
        TeamRole role = form.role();
        if (binding.hasErrors() || userId == null || role == null) {
            return invalid(model, response, team(principal, teamId, model));
        }
        admin.assignRole(actor(principal), teamId, userId, role);
        return "redirect:/admin/teams/" + teamId;
    }

    @PostMapping("/{teamId}/members/{userId}/remove")
    String revoke(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable long teamId,
            @PathVariable long userId) {
        admin.revokeRole(actor(principal), teamId, userId);
        return "redirect:/admin/teams/" + teamId;
    }

    /** Refusals go through Spring's error handling, so the status (and later the shell's error page) is uniform. */
    @ExceptionHandler(TeamAdminException.class)
    void refused(TeamAdminException e, HttpServletResponse response) throws IOException {
        response.sendError(
                switch (e.kind()) {
                    case NOT_FOUND -> HttpStatus.NOT_FOUND.value();
                    case FORBIDDEN -> HttpStatus.FORBIDDEN.value();
                    case CONFLICT -> HttpStatus.CONFLICT.value();
                    case INVALID -> HttpStatus.UNPROCESSABLE_CONTENT.value();
                });
    }

    private Actor actor(TrailkeepOidcUser principal) {
        long userId = principal.account().id();
        return new Actor(directory.permissionsOf(userId), principal.account().displayName());
    }

    private static String invalid(Model model, HttpServletResponse response, String view) {
        response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
        model.addAttribute("invalid", true);
        return view;
    }
}
