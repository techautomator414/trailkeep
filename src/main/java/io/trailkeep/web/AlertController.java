// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.notification.UserAlertService;
import io.trailkeep.security.TrailkeepOidcUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The Alerts page (BUILD_PLAN §4.7 "in-app list"): {@code GET /alerts} lists the person's alerts newest first, 50 to
 * a page with plain previous and next links, and reading it marks nothing read. "Mark read" and "Delete" are POSTs
 * with CSRF that come back to the same page; another person's alert, or an unknown id, is 404.
 */
@Controller
class AlertController {

    private final UserAlertService alerts;

    AlertController(UserAlertService alerts) {
        this.alerts = alerts;
    }

    @GetMapping("/alerts")
    String list(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @RequestParam(name = "page", defaultValue = "1") String page,
            Model model) {
        model.addAttribute("a", alerts.alerts(principal.userId(), page(page)));
        return "web/alerts";
    }

    @PostMapping("/alerts/{id}/read")
    String markRead(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String id,
            @RequestParam(name = "page", defaultValue = "1") String page) {
        alerts.markRead(principal.userId(), number(id, -1));
        return back(page);
    }

    @PostMapping("/alerts/{id}/delete")
    String delete(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String id,
            @RequestParam(name = "page", defaultValue = "1") String page) {
        alerts.delete(principal.userId(), number(id, -1));
        return back(page);
    }

    private static String back(String page) {
        return "redirect:/alerts?page=" + page(page);
    }

    /** A page number from the request; anything unreadable, or out of range, is page 1 or the last page. */
    private static int page(String value) {
        return Math.clamp(number(value, 1), 1, Integer.MAX_VALUE);
    }

    /** A number from the request; anything unreadable is the fallback (page 1, or an id that matches no alert). */
    private static long number(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            return fallback;
        }
    }
}
