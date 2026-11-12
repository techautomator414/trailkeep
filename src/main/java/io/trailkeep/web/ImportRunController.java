// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.importer.ImportRunService;
import io.trailkeep.importer.ImportRunService.Figure;
import io.trailkeep.importer.ImportRunService.RunDetail;
import io.trailkeep.importer.ImportRunService.RunSummary;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.Paged;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.TeamAdminService.TeamSummary;
import io.trailkeep.user.UserDirectoryService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The Import runs screen (WP-26 part s, design §3 steps 0 and 4, BUILD_PLAN §3.1): System Admins and Compliance Admins
 * see every run, newest first, and each run with its issues per class and the figures of its last completed dry run
 * ({@code IMPORT_RUN_SUMMARY}, R8), teams by name and times in {@code app.timezone}. {@link ImportRunService} runs
 * first on every request, so anyone else gets 403, and then an unknown run 404. It shows codes, times and counts only.
 */
@Controller
@RequestMapping("/admin/import-runs")
class ImportRunController {

    /**
     * A run in the list.
     *
     * @param id the run's id
     * @param kind its kind code
     * @param status its status code
     * @param requested when it was requested, in {@code app.timezone}, to the minute
     * @param updated when it last changed, in {@code app.timezone}, to the minute
     */
    record RunLine(long id, String kind, String status, LocalDateTime requested, LocalDateTime updated) {}

    /**
     * A dry-run figure as the screen shows it.
     *
     * @param metric what is counted
     * @param dimension its breakdown code, or {@code null} for a total
     * @param team the team's name when the figure is per team, else {@code null}
     * @param value the number
     */
    record FigureLine(
            String metric,
            @Nullable String dimension,
            @Nullable String team,
            long value) {}

    private final ImportRunService imports;
    private final UserDirectoryService directory;
    private final CasePage page;
    private final ZoneId zone;

    ImportRunController(ImportRunService imports, UserDirectoryService directory, CasePage page, AppProperties app) {
        this.imports = imports;
        this.directory = directory;
        this.page = page;
        this.zone = app.timezone();
    }

    @GetMapping
    String list(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @RequestParam(defaultValue = "1") String page,
            Model model) {
        Paged<RunSummary> runs = imports.runs(this.page.actor(principal), (int) number(page, 1));
        model.addAttribute(
                "runs",
                new Paged<>(
                        runs.page(),
                        runs.pages(),
                        runs.total(),
                        runs.rows().stream().map(this::line).toList()));
        return "importer/runs";
    }

    @GetMapping("/{id}")
    String show(@AuthenticationPrincipal TrailkeepOidcUser principal, @PathVariable String id, Model model) {
        Actor actor = page.actor(principal);
        RunDetail run = imports.run(actor, number(id, -1));
        List<Long> people = new ArrayList<>(List.of(run.requestedBy()));
        if (run.approvedBy() != null) {
            people.add(run.approvedBy());
        }
        Map<Long, String> names = directory.displayNames(people);
        Map<Long, String> teams =
                directory.allTeams().stream().collect(Collectors.toMap(TeamSummary::id, TeamSummary::name));
        model.addAttribute("r", run);
        model.addAttribute("line", line(run.summary()));
        model.addAttribute("requestedBy", names.getOrDefault(run.requestedBy(), ""));
        model.addAttribute("approvedBy", run.approvedBy() == null ? null : names.getOrDefault(run.approvedBy(), ""));
        model.addAttribute("approvedAt", run.approvedAt() == null ? null : local(run.approvedAt()));
        model.addAttribute(
                "figures", run.figures().stream().map(f -> figure(f, teams)).toList());
        return "importer/run";
    }

    private RunLine line(RunSummary run) {
        return new RunLine(run.id(), run.kind(), run.status(), local(run.requestedAt()), local(run.updatedAt()));
    }

    private static FigureLine figure(Figure figure, Map<Long, String> teams) {
        String team = figure.teamId() == null
                ? null
                : Objects.requireNonNullElse(teams.get(figure.teamId()), String.valueOf(figure.dimension()));
        return new FigureLine(figure.metric(), figure.dimension(), team, figure.value());
    }

    private LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, zone).truncatedTo(ChronoUnit.MINUTES);
    }

    /** A number as sent; anything unreadable is the fallback (the first page, or a run that does not exist). */
    private static long number(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            return fallback;
        }
    }
}
