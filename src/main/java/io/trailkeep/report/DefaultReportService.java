// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import io.trailkeep.casefile.CaseReportService;
import io.trailkeep.casefile.CaseReportService.AgingRow;
import io.trailkeep.casefile.CaseReportService.ComplianceRow;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamAdminService.TeamSummary;
import io.trailkeep.user.UserDirectoryService;
import io.trailkeep.user.UserPermissions;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Builds the reports from {@link CaseReportService}'s counts; see {@link ReportService}. Team and owner names come
 * from the directory; aging lines sort by team then owner name, and compliance counts, ordered by team id and case
 * type, by team name alone: the sort is stable, so the case types stay in order within each team.
 */
@Service
class DefaultReportService implements ReportService {

    private final CaseReportService counts;
    private final UserDirectoryService directory;
    private final InstantSource clock;
    private final ZoneId zone;

    DefaultReportService(
            CaseReportService counts, UserDirectoryService directory, InstantSource clock, AppProperties app) {
        this.counts = counts;
        this.directory = directory;
        this.clock = clock;
        this.zone = app.timezone();
    }

    @Override
    public AgingReport aging(Actor actor) {
        allowed(actor);
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        List<AgingRow> rows = counts.aging(actor, today);
        Map<Long, String> teams = teams(rows.stream().map(AgingRow::teamId).toList());
        Map<Long, String> owners =
                directory.displayNames(rows.stream().map(AgingRow::ownerId).toList());
        List<AgingLine> lines = rows.stream()
                .map(row -> new AgingLine(
                        teams.getOrDefault(row.teamId(), ""), owners.getOrDefault(row.ownerId(), ""), row))
                .sorted(Comparator.comparing(AgingLine::team).thenComparing(AgingLine::owner))
                .toList();
        AgingRow total = new AgingRow(
                0,
                0,
                sum(rows, AgingRow::upTo30),
                sum(rows, AgingRow::upTo60),
                sum(rows, AgingRow::upTo90),
                sum(rows, AgingRow::over90),
                sum(rows, AgingRow::onTrack),
                sum(rows, AgingRow::atRisk),
                sum(rows, AgingRow::overdue),
                sum(rows, AgingRow::noSla));
        return new AgingReport(today, lines, total);
    }

    @Override
    public ComplianceReport compliance(Actor actor, @Nullable String fromText, @Nullable String toText) {
        allowed(actor);
        LocalDate to = day(toText, LocalDate.ofInstant(clock.instant(), zone));
        LocalDate from = day(fromText, to.minusDays(DEFAULT_DAYS - 1));
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new ReportException(Kind.INVALID, "a period runs forwards and is at most " + MAX_DAYS + " days");
        }
        List<ComplianceRow> rows = counts.compliance(actor, from, to);
        Map<Long, String> teams = teams(rows.stream().map(ComplianceRow::teamId).toList());
        List<ComplianceLine> lines = rows.stream()
                .map(row -> new ComplianceLine(teams.getOrDefault(row.teamId(), ""), row, share(row)))
                .sorted(Comparator.comparing(ComplianceLine::team))
                .toList();
        ComplianceRow total = new ComplianceRow(
                0,
                "",
                sum(rows, ComplianceRow::met),
                sum(rows, ComplianceRow::breached),
                sum(rows, ComplianceRow::atRisk),
                sum(rows, ComplianceRow::overdue));
        return new ComplianceReport(from, to, lines, total, share(total));
    }

    /** Somebody with a team role or Compliance Admin; a System Admin alone runs no report (§3.2). */
    private static void allowed(Actor actor) {
        UserPermissions permissions = actor.permissions();
        if (permissions.teamRoles().isEmpty() && !permissions.has(GlobalRole.COMPLIANCE_ADMIN)) {
            throw new ReportException(Kind.FORBIDDEN, "reports need a team role or Compliance Admin");
        }
    }

    /** A {@code yyyy-mm-dd} date, or the given day when blank; {@code INVALID} when it does not parse. */
    private static LocalDate day(@Nullable String text, LocalDate whenBlank) {
        try {
            return text == null || text.isBlank() ? whenBlank : LocalDate.parse(text);
        } catch (DateTimeParseException malformed) {
            throw new ReportException(Kind.INVALID, "dates are yyyy-mm-dd");
        }
    }

    private Map<Long, String> teams(List<Long> ids) {
        return directory.teams(ids.stream().distinct().toList()).values().stream()
                .collect(Collectors.toUnmodifiableMap(TeamSummary::id, TeamSummary::name));
    }

    private static <T> long sum(List<T> rows, Function<T, Long> column) {
        return rows.stream().mapToLong(column::apply).sum();
    }

    /** Met as a whole percent of met plus breached, rounded half up; {@code null} when nothing closed. */
    private static @Nullable Integer share(ComplianceRow row) {
        long closed = row.met() + row.breached();
        return closed == 0 ? null : (int) ((row.met() * 200 + closed) / (closed * 2));
    }
}
