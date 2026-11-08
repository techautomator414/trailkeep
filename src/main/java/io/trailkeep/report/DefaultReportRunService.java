// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.report.ReportExportService.Csv;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Paged;
import io.trailkeep.shared.html.StoredReportSanitizer;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamAdminService.TeamSummary;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserDirectoryService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the stored runs; see {@link ReportRunService}. The list selects the columns it shows, never the stored HTML
 * or CSV; a Compliance Admin reads every team's runs and anyone else the runs of the teams they manage.
 */
@Service
@Transactional(readOnly = true)
class DefaultReportRunService implements ReportRunService {

    /** A run id as a URL may carry it: digits only, short enough to parse. */
    private static final Pattern ID = Pattern.compile("[0-9]{1,18}");

    private final EntityManager entityManager;
    private final UserDirectoryService directory;
    private final StoredReportSanitizer sanitizer;
    private final AuditRecorder audit;

    DefaultReportRunService(
            EntityManager entityManager,
            UserDirectoryService directory,
            StoredReportSanitizer sanitizer,
            AuditRecorder audit) {
        this.entityManager = entityManager;
        this.directory = directory;
        this.sanitizer = sanitizer;
        this.audit = audit;
    }

    /**
     * What the audit trail keeps of a run's download.
     *
     * @param team the run's team
     * @param asOf the day the report is as of
     * @param lines how many lines it held
     */
    record Downloaded(long team, String asOf, int lines) {}

    @Override
    public boolean readsRuns(Actor actor) {
        return everyTeam(actor) || !managed(actor).isEmpty();
    }

    @Override
    public Paged<RunSummary> runs(Actor actor, int page) {
        if (!readsRuns(actor)) {
            throw notFound();
        }
        boolean all = everyTeam(actor);
        Set<Long> teams = managed(actor);
        return Paged.of(page, PAGE_SIZE, () -> count(all, teams), offset -> rows(all, teams, offset));
    }

    @Override
    public RunView run(Actor actor, String id) {
        ReportRun run = readable(actor, id);
        String team = directory.team(run.getTeamId()).map(TeamSummary::name).orElse("");
        RunSummary summary = new RunSummary(run.getId(), team, run.getAsOf(), run.getLineCount());
        return new RunView(summary, sanitizer.sanitize(run.getHtml()));
    }

    @Override
    @Transactional
    public Csv csv(Actor actor, String id) {
        ReportRun run = readable(actor, id);
        audit.record(new AuditEntry(
                actor.permissions().userId(),
                actor.displayName(),
                AuditOrigin.UI,
                null,
                "REPORT_RUN",
                run.getId(),
                "REPORT_RUN_DOWNLOADED",
                audit.diff(null, new Downloaded(run.getTeamId(), run.getAsOf().toString(), run.getLineCount()))));
        return new Csv("aging-run-" + run.getId() + "-" + run.getAsOf() + ".csv", run.getCsv());
    }

    /** The run, if the id is well formed, names a run and the actor may read it; {@code NOT_FOUND} otherwise. */
    private ReportRun readable(Actor actor, String id) {
        ReportRun run = ID.matcher(id).matches() ? entityManager.find(ReportRun.class, Long.parseLong(id)) : null;
        if (run == null || !(everyTeam(actor) || managed(actor).contains(run.getTeamId()))) {
            throw notFound();
        }
        return run;
    }

    private long count(boolean all, Set<Long> teams) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<ReportRun> run = query.from(ReportRun.class);
        query.select(cb.count(run));
        if (!all) {
            query.where(run.<Long>get("teamId").in(teams));
        }
        return entityManager.createQuery(query).getSingleResult();
    }

    private List<RunSummary> rows(boolean all, Set<Long> teams, int offset) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<ReportRun> run = query.from(ReportRun.class);
        query.select(cb.tuple(run.get("id"), run.get("teamId"), run.get("asOf"), run.get("lineCount")))
                .orderBy(cb.desc(run.get("ranAt")), cb.desc(run.get("id")));
        if (!all) {
            query.where(run.<Long>get("teamId").in(teams));
        }
        List<Tuple> found = entityManager
                .createQuery(query)
                .setFirstResult(offset)
                .setMaxResults(PAGE_SIZE)
                .getResultList();
        Map<Long, String> names =
                directory
                        .teams(found.stream()
                                .map(row -> row.get(1, Long.class))
                                .distinct()
                                .toList())
                        .values()
                        .stream()
                        .collect(Collectors.toUnmodifiableMap(TeamSummary::id, TeamSummary::name));
        return found.stream()
                .map(row -> new RunSummary(
                        row.get(0, Long.class),
                        names.getOrDefault(row.get(1, Long.class), ""),
                        row.get(2, LocalDate.class),
                        row.get(3, Integer.class)))
                .toList();
    }

    private static boolean everyTeam(Actor actor) {
        return actor.permissions().has(GlobalRole.COMPLIANCE_ADMIN);
    }

    /** The teams the actor manages; empty for a disabled user, who holds no role. */
    private static Set<Long> managed(Actor actor) {
        return actor.permissions().teamRoles().entrySet().stream()
                .filter(role -> role.getValue() == TeamRole.MANAGER)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static ReportException notFound() {
        return new ReportException(Kind.NOT_FOUND, "no such run, or not one the actor reads");
    }
}
