// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseExportService;
import io.trailkeep.casefile.CaseExportService.Ready;
import io.trailkeep.casefile.CaseExportService.TooMany;
import io.trailkeep.casefile.CaseListQuery;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.InstantSource;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;

/**
 * CSV export of the cases list (BUILD_PLAN §4.4 "Export CSV"): {@code GET /cases/export.csv} with the list's own query
 * string, read by {@link CaseListQuery#parse} (422 for a value no form offers). {@code details=true} asks for the
 * subject and the description; {@link CaseExportService} honours it only for a Manager of the case's team or a
 * Compliance Admin and silently redacts for anyone else. A filter matching more than {@code app.export.max-rows}
 * cases is 422: the cases page again, with the reason next to the export control. The file is an attachment named
 * after the time of export in {@code app.timezone}, written by the response itself.
 */
@Controller
class CaseExportController {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final CaseExportService exports;
    private final CaseListPage list;
    private final CasePage page;
    private final InstantSource clock;
    private final ZoneId zone;

    CaseExportController(
            CaseExportService exports, CaseListPage list, CasePage page, InstantSource clock, AppProperties app) {
        this.exports = exports;
        this.list = list;
        this.page = page;
        this.clock = clock;
        this.zone = app.timezone();
    }

    @GetMapping("/cases/export.csv")
    ModelAndView export(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @RequestParam MultiValueMap<String, String> parameters,
            HttpServletResponse response) {
        Actor actor = page.actor(principal);
        CaseListQuery query = CaseListQuery.parse(parameters);
        boolean details = "true".equals(parameters.getFirst("details"));
        return switch (exports.export(actor, query, details)) {
            case Ready ready ->
                new ModelAndView(new CsvView(
                        ready, "cases-" + STAMP.format(clock.instant().atZone(zone)) + ".csv"));
            case TooMany tooMany -> {
                response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
                ExtendedModelMap model = new ExtendedModelMap();
                list.fill(actor, query, model);
                model.addAttribute("exportProblem", tooMany);
                yield new ModelAndView("casefile/cases", model);
            }
        };
    }

    /** Writes the CSV as the response body, an attachment that is never cached or sniffed. */
    private record CsvView(Ready export, String fileName) implements View {

        @Override
        public String getContentType() {
            return "text/csv;charset=UTF-8";
        }

        @Override
        public void render(@Nullable Map<String, ?> model, HttpServletRequest request, HttpServletResponse response)
                throws IOException {
            response.setContentType(getContentType());
            // The name is "cases-" and digits, so it needs no quoting beyond the quotes.
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
            export.writeTo(response.getOutputStream());
        }
    }
}
