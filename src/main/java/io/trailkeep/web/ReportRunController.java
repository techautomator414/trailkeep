// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.report.ReportRunService;
import io.trailkeep.report.ReportScheduleService;
import io.trailkeep.security.TrailkeepOidcUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The scheduled report runs (WP-23 part b), where the {@code report-ready} mail and alert lead: {@code /reports/runs}
 * lists them newest first, 20 to a page; {@code /reports/runs/{id}} shows one as it was sent, its stored HTML
 * sanitized and written through {@code tk:safe}; {@code /reports/runs/{id}.csv} downloads its stored CSV as an
 * attachment, never cached or sniffed, and audited. {@link ReportRunService} decides who reads what: anyone but an
 * enabled Manager of the run's team or a Compliance Admin gets 404, as does a malformed or unknown id. Plain GETs; the
 * only write is the download's audit row. The list links Compliance Admins to the report schedules
 * ({@link ReportScheduleController}).
 */
@Controller
class ReportRunController {

    private final ReportRunService runs;
    private final ReportScheduleService schedules;
    private final CasePage page;

    ReportRunController(ReportRunService runs, ReportScheduleService schedules, CasePage page) {
        this.runs = runs;
        this.schedules = schedules;
        this.page = page;
    }

    @GetMapping("/reports/runs")
    String runs(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @RequestParam(defaultValue = "1") String page,
            Model model) {
        Actor actor = this.page.actor(principal);
        model.addAttribute("runs", runs.runs(actor, number(page)));
        model.addAttribute("administers", schedules.administers(actor));
        return "report/runs";
    }

    @GetMapping("/reports/runs/{id}")
    String run(@AuthenticationPrincipal TrailkeepOidcUser principal, @PathVariable String id, Model model) {
        model.addAttribute("v", runs.run(page.actor(principal), id));
        return "report/run";
    }

    /** The file as the reports' CSV are sent; its name is the run's id and day, so it needs no encoding. */
    @GetMapping("/reports/runs/{id}.csv")
    ResponseEntity<String> csv(@AuthenticationPrincipal TrailkeepOidcUser principal, @PathVariable String id) {
        return ReportController.attachment(runs.csv(page.actor(principal), id));
    }

    /** A page number as sent; anything unreadable is the first page. */
    private static int number(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException malformed) {
            return 1;
        }
    }
}
