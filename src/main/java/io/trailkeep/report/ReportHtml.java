// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import io.trailkeep.report.ReportService.AgingReport;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Renders a report's HTML outside a request, for a scheduled run: the {@code report} fragment of the very template the
 * report page shows ({@code report/aging}), with the application's messages, so a run holds what the page shows.
 */
@Component
class ReportHtml {

    private final ITemplateEngine engine;

    ReportHtml(ITemplateEngine engine) {
        this.engine = engine;
    }

    /**
     * The aging report's tables and lists.
     *
     * @param report the report
     * @return its HTML, escaped by the template
     */
    String aging(AgingReport report) {
        Context context = new Context(Locale.ROOT, Map.of("r", report, "listed", ReportService.LISTED));
        return engine.process("report/aging", Set.of("report"), context);
    }
}
