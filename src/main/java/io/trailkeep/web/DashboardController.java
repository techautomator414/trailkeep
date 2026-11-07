// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.closure.ClosureService;
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
 * The landing page after login. "Needs attention" lists the closure requests waiting for the person's decision
 * (WP-19, "Pending my approval", oldest first, each with Approve) and the update requests addressed to them (WP-18),
 * newest first, on cases they read; KPI tiles, "My cases" and overdue cases arrive with WP-20.
 */
@Controller
class DashboardController {

    private final UpdateRequestService requests;
    private final ClosureService closures;
    private final CasePage page;

    DashboardController(UpdateRequestService requests, ClosureService closures, CasePage page) {
        this.requests = requests;
        this.closures = closures;
        this.page = page;
    }

    @GetMapping("/")
    String dashboard(@AuthenticationPrincipal @Nullable OidcUser user, Model model) {
        model.addAttribute("displayName", user == null ? "" : String.valueOf(user.getFullName()));
        @Nullable Actor actor = user instanceof TrailkeepOidcUser person ? page.actor(person) : null;
        model.addAttribute("attention", actor == null ? List.of() : requests.openFor(actor));
        model.addAttribute("approvals", actor == null ? List.of() : closures.awaitingMyApproval(actor));
        return "web/dashboard";
    }
}
