// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.user.UserPreferenceService;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The person's preferences (WP-27, BUILD_PLAN §3.1, §10.1): {@code GET /preferences} shows the single-key shortcuts
 * switch ({@code /} and {@code N}, on by default, WCAG 2.1.4) and {@code POST /preferences} saves it, with CSRF, and
 * comes back to the page with a confirmation. Only the signed-in person's own preferences are read or written; the
 * person is never taken from the request.
 */
@Controller
class PreferencesController {

    private final UserPreferenceService preferences;

    PreferencesController(UserPreferenceService preferences) {
        this.preferences = preferences;
    }

    @GetMapping("/preferences")
    String show(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @RequestParam Map<String, String> parameters,
            Model model) {
        model.addAttribute("shortcuts", preferences.shortcutsEnabled(principal.userId()));
        // Read here and not as ${param.saved} in the template: a malformed query string makes the first read throw,
        // and thrown while rendering it was logged by Thymeleaf at ERROR with a stack trace for every probe.
        model.addAttribute("saved", parameters.containsKey("saved"));
        return "web/preferences";
    }

    @PostMapping("/preferences")
    String save(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @RequestParam(name = "shortcuts", required = false) String shortcuts) {
        preferences.setShortcutsEnabled(principal.userId(), principal.displayName(), shortcuts != null);
        return "redirect:/preferences?saved";
    }
}
