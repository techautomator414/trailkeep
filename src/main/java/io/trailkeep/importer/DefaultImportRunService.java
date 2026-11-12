// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Paged;
import io.trailkeep.user.GlobalRole;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads {@code IMPORT_RUN} for other modules with JPQL queries of its own columns, so {@link ImportRun} stays as it is,
 * and a run's issues and figures for the Import runs screen. Not tied to a profile: the web application and the
 * importer both have it.
 */
@Service
@Transactional(readOnly = true)
class DefaultImportRunService implements ImportRunService {

    private static final String COLUMNS = "SELECT r.id, r.kind, r.status, r.requestedAt, r.updatedAt, r.rowsLoaded,"
            + " r.approvedBy, r.approvedAt, r.requestedBy, r.scanMode, r.rowsStaged FROM ImportRun r";
    private static final String NEWEST_FIRST = COLUMNS + " ORDER BY r.requestedAt DESC, r.id DESC";
    private static final int PAGE_SIZE = 20;

    /** The statuses from which the loaded count means something. */
    private static final Set<ImportRun.Status> LOADING = Set.of(
            ImportRun.Status.LOADING, ImportRun.Status.LOADED, ImportRun.Status.RECONCILED, ImportRun.Status.FAILED);

    /** The figures whose dimension is a Trailkeep team id ({@link DryRunSummary}). */
    private static final Set<String> PER_TEAM =
            Set.of("VISIBILITY_STANDARD", "VISIBILITY_RESTRICTED", "DEFAULT_OWNER_CASES");

    /** The issue counts, shown from {@code IMPORT_ISSUE} itself, and the forecast day, shown as a date. */
    private static final String ISSUES = "ISSUES";

    private static final String FORECAST_DAY = "SLA_FORECAST_DAY";

    private final EntityManager entityManager;
    private final ImportIssueRepository issues;

    DefaultImportRunService(EntityManager entityManager, ImportIssueRepository issues) {
        this.entityManager = entityManager;
        this.issues = issues;
    }

    @Override
    public Optional<RunSummary> latest() {
        return entityManager.createQuery(NEWEST_FIRST, Object[].class).setMaxResults(1).getResultList().stream()
                .findFirst()
                .map(DefaultImportRunService::summary);
    }

    @Override
    public Paged<RunSummary> runs(Actor actor, int page) {
        allowed(actor);
        return Paged.of(
                page,
                PAGE_SIZE,
                () -> entityManager
                        .createQuery("SELECT count(r) FROM ImportRun r", Long.class)
                        .getSingleResult(),
                offset -> entityManager
                        .createQuery(NEWEST_FIRST, Object[].class)
                        .setFirstResult(offset)
                        .setMaxResults(PAGE_SIZE)
                        .getResultList()
                        .stream()
                        .map(DefaultImportRunService::summary)
                        .toList());
    }

    @Override
    public RunDetail run(Actor actor, long id) {
        allowed(actor);
        Object[] row = entityManager
                .createQuery(COLUMNS + " WHERE r.id = :id", Object[].class)
                .setParameter("id", id)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new ImportRunException(Kind.NOT_FOUND, "no such import run"));
        List<Figure> figures = new ArrayList<>();
        LocalDate forecastDay = null;
        for (Object[] figure : entityManager
                .createQuery(
                        "SELECT s.metric, s.dimension, s.value FROM ImportRunSummary s WHERE s.runId = :id"
                                + " ORDER BY s.metric, s.dimension",
                        Object[].class)
                .setParameter("id", id)
                .getResultList()) {
            String metric = (String) figure[0];
            String dimension = (String) figure[1];
            long value = (Long) figure[2];
            if (FORECAST_DAY.equals(metric)) {
                forecastDay = LocalDate.ofEpochDay(value);
            } else if (!ISSUES.equals(metric)) {
                Long team = PER_TEAM.contains(metric) && dimension != null ? Long.valueOf(dimension) : null;
                figures.add(new Figure(metric, dimension, team, value));
            }
        }
        ImportRun.ScanMode mode = (ImportRun.ScanMode) row[9];
        return new RunDetail(
                summary(row),
                (Long) row[8],
                (Long) row[6],
                (Instant) row[7],
                mode == null ? null : mode.name(),
                (Long) row[10],
                issueCounts(id),
                figures,
                forecastDay);
    }

    /** The run's issues per class, from every stage so far: fatal first, then by entity and code. */
    private List<IssueCount> issueCounts(long id) {
        return issues.countByCode(id).stream()
                .sorted(Comparator.<Object[], ImportIssue.Severity>comparing(c -> (ImportIssue.Severity) c[1])
                        .thenComparing(c -> (ImportIssue.Subject) c[0])
                        .thenComparing(c -> (String) c[2]))
                .map(c -> new IssueCount(
                        ((ImportIssue.Subject) c[0]).name(), ((ImportIssue.Severity) c[1]).name(), (String) c[2], (Long)
                                c[3]))
                .toList();
    }

    /** A run from the columns of {@link #COLUMNS}; a dry run a Compliance Admin has signed off reads as approved. */
    private static RunSummary summary(Object[] row) {
        ImportRun.Status status = (ImportRun.Status) row[2];
        @Nullable Long approvedBy = (Long) row[6];
        ImportRun.Status shown =
                status == ImportRun.Status.DRY_RUN && approvedBy != null ? ImportRun.Status.APPROVED : status;
        return new RunSummary(
                (Long) row[0],
                ((ImportRun.Kind) row[1]).name(),
                shown.name(),
                (Instant) row[3],
                (Instant) row[4],
                LOADING.contains(status) ? (Long) row[5] : null);
    }

    private static void allowed(Actor actor) {
        if (Collections.disjoint(
                actor.permissions().globalRoles(), List.of(GlobalRole.SYSTEM_ADMIN, GlobalRole.COMPLIANCE_ADMIN))) {
            throw new ImportRunException(Kind.FORBIDDEN, "the Import runs screen is for System and Compliance Admins");
        }
    }
}
