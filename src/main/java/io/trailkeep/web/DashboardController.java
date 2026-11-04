// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** The landing page after login. KPI tiles and lists arrive with WP-20; this is the shell's first page. */
@Controller
class DashboardController {

    @GetMapping("/")
    String dashboard(@AuthenticationPrincipal @Nullable OidcUser user, Model model) {
        model.addAttribute("displayName", user == null ? "" : String.valueOf(user.getFullName()));
        return "web/dashboard";
    }
}
