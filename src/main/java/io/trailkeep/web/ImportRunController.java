// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.CaseService.CaseRef;
import io.trailkeep.importer.ImportRunException;
import io.trailkeep.importer.ImportRunService;
import io.trailkeep.importer.ImportRunService.Figure;
import io.trailkeep.importer.ImportRunService.RunDetail;
import io.trailkeep.importer.ImportRunService.RunSummary;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Paged;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.TeamAdminService.TeamSummary;
import io.trailkeep.user.UserDirectoryService;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The Import runs screen (WP-26 part s, design §3 steps 0 and 4, BUILD_PLAN §3.1): System Admins and Compliance Admins
 * see every run, newest first, and each run with its issues per class and the figures of its last completed dry run
 * ({@code IMPORT_RUN_SUMMARY}, R8), teams by name and times in {@code app.timezone}. {@link ImportRunService} runs
 * first on every request, so anyone else gets 403, and then an unknown run 404. It shows codes, times and counts only,
 * and the run's closure worklist (WP-26 part y3, design §3.1): the cases its dry run found closed in the source, by
 * number and title, linked, 20 to a page, resolved under the viewer's own case scope, so a case they may not read is
 * only counted, never named.
 *
 * <p>Both may request a run naming a source id ({@code POST /admin/import-runs}, from a native dialog on the list or
 * {@code /admin/import-runs/new}, which a malformed id re-renders with 422); a Compliance Admin may also choose a
 * relink run ({@code kind=RELINK}, WP-26 part z2), offered to nobody else. A Compliance Admin may sign off a run
 * whose last dry run completed with no FATAL issue, acknowledging every WARNING class; the service checks it all
 * again, and the run's page comes back with 409 and Reload when the run changed, or 422 with the acknowledgements
 * kept.
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
    private final CaseService cases;
    private final UserDirectoryService directory;
    private final CasePage page;
    private final ZoneId zone;

    ImportRunController(
            ImportRunService imports,
            CaseService cases,
            UserDirectoryService directory,
            CasePage page,
            AppProperties app) {
        this.imports = imports;
        this.cases = cases;
        this.directory = directory;
        this.page = page;
        this.zone = app.timezone();
    }

    @GetMapping
    String list(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @RequestParam(defaultValue = "1") String page,
            Model model) {
        Actor actor = this.page.actor(principal);
        Paged<RunSummary> runs = imports.runs(actor, (int) number(page, 1));
        model.addAttribute("relinks", imports.signsOff(actor));
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
    String show(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String id,
            @RequestParam(defaultValue = "1") String worklist,
            Model model) {
        return render(page.actor(principal), number(id, -1), (int) number(worklist, 1), List.of(), false, false, model);
    }

    @GetMapping("/new")
    String requestForm(@AuthenticationPrincipal TrailkeepOidcUser principal, Model model) {
        Actor actor = page.actor(principal);
        imports.checkAccess(actor);
        return requestPage("", false, false, imports.signsOff(actor), model);
    }

    @PostMapping
    String request(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @RequestParam(defaultValue = "") String sourceId,
            @RequestParam(defaultValue = "IMPORT") String kind,
            HttpServletResponse response,
            Model model) {
        Actor actor = page.actor(principal);
        boolean relink = "RELINK".equals(kind);
        try {
            return "redirect:/admin/import-runs/" + imports.request(actor, sourceId, relink);
        } catch (ImportRunException refused) {
            if (refused.kind() != Kind.INVALID) {
                throw refused;
            }
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            return requestPage(sourceId, true, relink, imports.signsOff(actor), model);
        }
    }

    @PostMapping("/{id}/sign-off")
    String signOff(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String id,
            @RequestParam(defaultValue = "") String version,
            @RequestParam(required = false) @Nullable List<String> acknowledged,
            HttpServletResponse response,
            Model model) {
        Actor actor = page.actor(principal);
        long run = number(id, -1);
        List<String> ticked = acknowledged == null ? List.of() : acknowledged;
        try {
            imports.signOff(actor, run, version(version), Set.copyOf(ticked));
        } catch (ImportRunException refused) {
            boolean conflict = refused.kind() == Kind.CONFLICT;
            if (!conflict && refused.kind() != Kind.INVALID) {
                throw refused;
            }
            response.setStatus((conflict ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_CONTENT).value());
            return render(actor, run, 1, ticked, conflict, !conflict, model);
        }
        return "redirect:/admin/import-runs/" + run;
    }

    private String render(
            Actor actor,
            long id,
            int worklist,
            List<String> ticked,
            boolean conflict,
            boolean unacknowledged,
            Model model) {
        RunDetail run = imports.run(actor, id);
        // Resolved under the viewer's own scope: a case they may not read is left out, only counted.
        Paged<Long> closed = imports.closureWorklist(actor, id, worklist);
        Map<Long, CaseRef> readable = cases.refs(actor, closed.rows());
        List<CaseRef> listed = closed.rows().stream()
                .map(readable::get)
                .filter(Objects::nonNull)
                .toList();
        model.addAttribute("worklist", new Paged<>(closed.page(), closed.pages(), closed.total(), listed));
        model.addAttribute("unlisted", closed.rows().size() - listed.size());
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
        model.addAttribute("approves", imports.signsOff(actor));
        model.addAttribute("acknowledged", ticked);
        model.addAttribute("conflict", conflict);
        model.addAttribute("unacknowledged", unacknowledged);
        return "importer/run";
    }

    private static String requestPage(String sourceId, boolean invalid, boolean relink, boolean relinks, Model model) {
        model.addAttribute("sourceId", sourceId);
        model.addAttribute("invalid", invalid);
        model.addAttribute("relink", relink);
        model.addAttribute("relinks", relinks);
        return "importer/request";
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

    /** The version a form was loaded with; anything unreadable can only be stale. */
    private static int version(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException unreadable) {
            return Integer.MIN_VALUE;
        }
    }
}
