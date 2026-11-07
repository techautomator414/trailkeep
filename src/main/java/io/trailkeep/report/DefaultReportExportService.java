// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.casefile.CaseReportService.AgingRow;
import io.trailkeep.casefile.CaseReportService.ComplianceRow;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.report.ReportService.AgingReport;
import io.trailkeep.report.ReportService.ComplianceReport;
import io.trailkeep.shared.csv.CsvFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.LongStream;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Writes {@link ReportService}'s reports as CSV and audits each export; see {@link ReportExportService}. */
@Service
class DefaultReportExportService implements ReportExportService {

    private static final List<String> AGING = List.of(
            "Team",
            "Owner",
            "0-30 days",
            "31-60 days",
            "61-90 days",
            "Over 90 days",
            "On track",
            "At risk",
            "Overdue",
            "No SLA");
    private static final List<String> COMPLIANCE = List.of(
            "Team", "Case type", "Closed within SLA", "Closed late", "At risk", "Overdue", "Share within SLA (%)");
    private static final String TOTAL = "Total";

    private final ReportService reports;
    private final AuditRecorder audit;

    DefaultReportExportService(ReportService reports, AuditRecorder audit) {
        this.reports = reports;
        this.audit = audit;
    }

    /**
     * What the audit trail keeps of a report export.
     *
     * @param report {@code aging} or {@code compliance}
     * @param period the day it is as of, or its first and last day
     * @param lines how many lines it held, the total row not counted
     */
    record Exported(String report, String period, int lines) {}

    @Override
    @Transactional
    public Csv aging(Actor actor) {
        AgingReport report = reports.aging(actor);
        List<List<String>> lines = new ArrayList<>();
        report.lines().forEach(line -> lines.add(agingCells(line.team(), line.owner(), line.counts())));
        lines.add(agingCells(TOTAL, "", report.total()));
        audited(
                actor,
                new Exported("aging", report.asOf().toString(), report.lines().size()));
        return csv("aging-" + report.asOf() + ".csv", AGING, lines);
    }

    @Override
    @Transactional
    public Csv compliance(Actor actor, @Nullable String from, @Nullable String to) {
        ComplianceReport report = reports.compliance(actor, from, to);
        List<List<String>> lines = new ArrayList<>();
        report.lines().forEach(line -> lines.add(complianceCells(line.team(), line.counts(), line.withinSla())));
        lines.add(complianceCells(TOTAL, report.total(), report.withinSla()));
        String period = report.from() + "-to-" + report.to();
        audited(actor, new Exported("compliance", period, report.lines().size()));
        return csv("sla-compliance-" + period + ".csv", COMPLIANCE, lines);
    }

    private void audited(Actor actor, Exported exported) {
        audit.record(new AuditEntry(
                actor.permissions().userId(),
                actor.displayName(),
                AuditOrigin.UI,
                null,
                "REPORT",
                null,
                "REPORT_EXPORTED",
                audit.diff(null, exported)));
    }

    private static List<String> agingCells(String team, String owner, AgingRow c) {
        return cells(
                team,
                owner,
                c.upTo30(),
                c.upTo60(),
                c.upTo90(),
                c.over90(),
                c.onTrack(),
                c.atRisk(),
                c.overdue(),
                c.noSla());
    }

    private static List<String> complianceCells(String team, ComplianceRow c, @Nullable Integer withinSla) {
        List<String> cells = cells(team, c.caseType(), c.met(), c.breached(), c.atRisk(), c.overdue());
        cells.add(Objects.toString(withinSla, ""));
        return cells;
    }

    private static List<String> cells(String first, String second, long... counts) {
        List<String> cells = new ArrayList<>(List.of(first, second));
        LongStream.of(counts).mapToObj(Long::toString).forEach(cells::add);
        return cells;
    }

    private static Csv csv(String fileName, List<String> header, List<List<String>> lines) {
        StringBuilder content = new StringBuilder(CsvFormat.BOM).append(CsvFormat.line(header));
        lines.forEach(line -> content.append(CsvFormat.line(line)));
        return new Csv(fileName, content.toString());
    }
}
