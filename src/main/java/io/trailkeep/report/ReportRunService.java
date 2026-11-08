// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.report.ReportExportService.Csv;
import io.trailkeep.shared.Paged;
import io.trailkeep.shared.html.SafeHtml;
import java.time.LocalDate;

/**
 * The scheduled report runs as they were sent (BUILD_PLAN §6 "Aging report delivery", WP-23 part b). A run is read
 * only by an enabled Manager of its team or an enabled Compliance Admin, the people it was sent to: for anyone else,
 * as for a malformed or unknown id, every method answers {@code NOT_FOUND}, so run ids cannot be probed (ADR-0005).
 * Permissions are the actor's, rebuilt from the database for the request; a disabled user holds none. The stored HTML
 * reaches a page only through {@link io.trailkeep.shared.html.StoredReportSanitizer}.
 */
public interface ReportRunService {

    /** Runs to a page of {@link #runs}. */
    int PAGE_SIZE = 20;

    /**
     * A run as the list shows it.
     *
     * @param id the run's id
     * @param team the team's name
     * @param asOf the day the report is as of
     * @param lines how many lines its table holds, the total row not counted
     */
    record RunSummary(long id, String team, LocalDate asOf, int lines) {}

    /**
     * One run with its report.
     *
     * @param run the run
     * @param html the stored report, sanitized
     */
    record RunView(RunSummary run, SafeHtml html) {}

    /**
     * Whether the actor may read any run at all: a Manager of some team or a Compliance Admin.
     *
     * @param actor the actor
     * @return {@code true} if so
     */
    boolean readsRuns(Actor actor);

    /**
     * The runs the actor may read, newest first, {@link #PAGE_SIZE} to a page; {@code NOT_FOUND} if {@link #readsRuns}
     * does not hold.
     *
     * @param actor the actor
     * @param page the page asked for, from 1; past the last shows the last
     * @return the page
     */
    Paged<RunSummary> runs(Actor actor, int page);

    /**
     * One run, its stored HTML sanitized.
     *
     * @param actor the actor
     * @param id the id as it appears in the URL
     * @return the run
     */
    RunView run(Actor actor, String id);

    /**
     * One run's stored CSV, audited ({@code REPORT_RUN_DOWNLOADED}) with the run, its team, its day and its line
     * count; a refused request is not audited.
     *
     * @param actor the actor
     * @param id the id as it appears in the URL
     * @return the file
     */
    Csv csv(Actor actor, String id);
}
