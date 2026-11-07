// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import io.trailkeep.casefile.CaseReportService.AgingRow;
import io.trailkeep.casefile.CaseReportService.ComplianceRow;
import io.trailkeep.casefile.CaseService.Actor;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The aging and SLA-compliance reports (BUILD_PLAN §4.5, WP-22) over the cases the actor reads: a Manager sees their
 * teams, a Compliance Admin every team, and a Reader or Analyst their teams without the restricted cases they cannot
 * read (§3.2 "Run reports / export", §3.3). Somebody with no team role and no Compliance Admin role (a System Admin)
 * is refused ({@code FORBIDDEN}). Counts only, aging by team and owner and compliance by team and case type, each
 * with a total row.
 */
public interface ReportService {

    /**
     * One aging line.
     *
     * @param team the team's name
     * @param owner the owner's display name
     * @param counts the counts
     */
    record AgingLine(String team, String owner, AgingRow counts) {}

    /**
     * The aging report.
     *
     * @param asOf the day ages count to, in {@code app.timezone}
     * @param lines by team name and case type
     * @param total the column totals
     */
    record AgingReport(LocalDate asOf, List<AgingLine> lines, AgingRow total) {

        /**
         * Copies the lines, so the record holds nothing mutable.
         *
         * @param asOf the day
         * @param lines the lines
         * @param total the totals
         */
        public AgingReport {
            lines = List.copyOf(lines);
        }
    }

    /**
     * One compliance line.
     *
     * @param team the team's name
     * @param counts the counts
     * @param withinSla the share closed within the SLA, in whole percent, or {@code null} when none closed
     */
    record ComplianceLine(
            String team, ComplianceRow counts, @Nullable Integer withinSla) {}

    /**
     * The SLA-compliance report.
     *
     * @param from the period's first day
     * @param to its last day
     * @param lines by team name and case type
     * @param total the column totals
     * @param withinSla the overall share closed within the SLA, or {@code null} when none closed
     */
    record ComplianceReport(
            LocalDate from,
            LocalDate to,
            List<ComplianceLine> lines,
            ComplianceRow total,
            @Nullable Integer withinSla) {

        /**
         * Copies the lines, so the record holds nothing mutable.
         *
         * @param from the first day
         * @param to the last day
         * @param lines the lines
         * @param total the totals
         * @param withinSla the overall share
         */
        public ComplianceReport {
            lines = List.copyOf(lines);
        }
    }

    /**
     * The aging report as of today.
     *
     * @param actor the actor
     * @return the report
     */
    AgingReport aging(Actor actor);

    /**
     * The SLA-compliance report for cases closed from {@code from} to {@code to} ({@code yyyy-mm-dd}, both inclusive;
     * by default the {@link #DEFAULT_DAYS} days to today). After the role check, {@code INVALID} for a date that does
     * not parse, a backwards period or one longer than {@link #MAX_DAYS} days.
     *
     * @param actor the actor
     * @param from the first day, or blank
     * @param to the last day, or blank
     * @return the report
     */
    ComplianceReport compliance(Actor actor, @Nullable String from, @Nullable String to);

    /** The longest compliance period, in days. */
    int MAX_DAYS = 366;

    /** The default compliance period, in days up to today. */
    int DEFAULT_DAYS = 30;
}
