// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.user.TeamAdminService.Actor;
import io.trailkeep.user.UserAdminService;
import io.trailkeep.user.UserDirectoryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * User administration (BUILD_PLAN §4.6): the user list and disabling or enabling a user. Authorization is the
 * service's, as for {@link TeamAdminController}: it rebuilds the actor's permissions on every request and refuses
 * anyone but a System Admin with a {@code DomainException} that {@code ErrorAdvice} renders.
 */
@Controller
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/admin/users")
class UserAdminController {

    private static final String USERS = "user/users";
    private static final String REDIRECT = "redirect:/admin/users";

    private final UserAdminService admin;
    private final UserDirectoryService directory;

    UserAdminController(UserAdminService admin, UserDirectoryService directory) {
        this.admin = admin;
        this.directory = directory;
    }

    @GetMapping
    String users(@AuthenticationPrincipal TrailkeepOidcUser principal, Model model) {
        model.addAttribute("users", admin.users(actor(principal)));
        model.addAttribute("self", principal.userId());
        return USERS;
    }

    @PostMapping("/{userId}/disable")
    String disable(@AuthenticationPrincipal TrailkeepOidcUser principal, @PathVariable long userId) {
        admin.disable(actor(principal), userId);
        return REDIRECT;
    }

    @PostMapping("/{userId}/enable")
    String enable(@AuthenticationPrincipal TrailkeepOidcUser principal, @PathVariable long userId) {
        admin.enable(actor(principal), userId);
        return REDIRECT;
    }

    private Actor actor(TrailkeepOidcUser principal) {
        return new Actor(directory.permissionsOf(principal.userId()), principal.displayName());
    }
}
