// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.report.ReportException;
import io.trailkeep.report.ReportService;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.DomainException.Kind;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The reports (BUILD_PLAN §4.5, WP-22 part a): {@code /reports} lists them, {@code /reports/aging} is the aging report
 * as of today and {@code /reports/compliance?from=&to=} the SLA-compliance report for a period (by default the last
 * 30 days). {@link ReportService} decides who may run them (403, before anything else) and checks the period: a bad
 * one is a 422 with the form marked. Plain GETs that change nothing.
 */
@Controller
class ReportController {

    private final ReportService reports;
    private final CasePage page;

    ReportController(ReportService reports, CasePage page) {
        this.reports = reports;
        this.page = page;
    }

    @GetMapping("/reports")
    String index() {
        return "report/index";
    }

    @GetMapping("/reports/aging")
    String aging(@AuthenticationPrincipal TrailkeepOidcUser principal, Model model) {
        model.addAttribute("r", reports.aging(page.actor(principal)));
        return "report/aging";
    }

    @GetMapping("/reports/compliance")
    String compliance(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            HttpServletResponse response,
            Model model) {
        try {
            ReportService.ComplianceReport report = reports.compliance(page.actor(principal), from, to);
            model.addAttribute("r", report);
            model.addAttribute("from", report.from().toString());
            model.addAttribute("to", report.to().toString());
        } catch (ReportException refused) {
            if (refused.kind() != Kind.INVALID) {
                throw refused;
            }
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("r", null);
            model.addAttribute("from", from == null ? "" : from);
            model.addAttribute("to", to == null ? "" : to);
        }
        return "report/compliance";
    }
}
