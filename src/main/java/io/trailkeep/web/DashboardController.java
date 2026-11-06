// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.updaterequest.UpdateRequestService;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The landing page after login. "Needs attention" lists the update requests addressed to the person (WP-18), newest
 * first, on cases they read; KPI tiles, "My cases" and overdue cases arrive with WP-20.
 */
@Controller
class DashboardController {

    private final UpdateRequestService requests;
    private final CasePage page;

    DashboardController(UpdateRequestService requests, CasePage page) {
        this.requests = requests;
        this.page = page;
    }

    @GetMapping("/")
    String dashboard(@AuthenticationPrincipal @Nullable OidcUser user, Model model) {
        model.addAttribute("displayName", user == null ? "" : String.valueOf(user.getFullName()));
        model.addAttribute(
                "attention",
                user instanceof TrailkeepOidcUser person ? requests.openFor(page.actor(person)) : List.of());
        return "web/dashboard";
    }
}
