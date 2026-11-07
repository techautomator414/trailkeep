// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.report.ReportException;
import io.trailkeep.report.ReportService;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.DomainException.Kind;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The reports (BUILD_PLAN §4.5, WP-22 part a), each linking to the other: {@code /reports/aging} is the aging report
 * as of today, where the header's "Reports" leads, and {@code /reports/compliance?from=&to=} the SLA-compliance report
 * for a period (by default the last 30 days). {@link ReportService} decides who may run them (403, before anything
 * else) and checks the period: a bad one is a 422 with the form marked. Plain GETs that change nothing.
 */
@Controller
class ReportController {

    private final ReportService reports;
    private final CasePage page;

    ReportController(ReportService reports, CasePage page) {
        this.reports = reports;
        this.page = page;
    }

    @GetMapping("/reports/aging")
    String aging(@AuthenticationPrincipal TrailkeepOidcUser principal, Model model) {
        model.addAttribute("r", reports.aging(page.actor(principal)));
        return "report/aging";
    }

    @GetMapping("/reports/compliance")
    String compliance(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            HttpServletResponse response,
            Model model) {
        try {
            ReportService.ComplianceReport report = reports.compliance(page.actor(principal), from, to);
            model.addAttribute("r", report);
            model.addAllAttributes(
                    Map.of("from", report.from().toString(), "to", report.to().toString()));
        } catch (ReportException refused) {
            if (refused.kind() != Kind.INVALID) {
                throw refused;
            }
            // The form comes back with what was sent, escaped by th:value.
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAllAttributes(Map.of("from", from, "to", to));
        }
        return "report/compliance";
    }
}
