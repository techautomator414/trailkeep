// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.audit.AuditChanges;
import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Paged;
import io.trailkeep.user.GlobalRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads {@code IMPORT_RUN} for other modules with JPQL queries of its own columns, and a run's issues and figures for
 * the Import runs screen; writes the run request and the sign-off, the runtime user's only writes to it (V10). Not tied
 * to a profile: the web application and the importer both have it. A sign-off is flushed inside its transaction, so a
 * dry run that moved the run's version on meanwhile is a {@code CONFLICT} rather than a sign-off of other figures.
 */
@Service
@Transactional(readOnly = true)
class DefaultImportRunService implements ImportRunService {

    private static final String COLUMNS = "SELECT r.id, r.kind, r.status, r.requestedAt, r.updatedAt, r.rowsLoaded,"
            + " r.approvedBy, r.approvedAt, r.requestedBy, r.scanMode, r.rowsStaged, r.acknowledgedWarnings"
            + " FROM ImportRun r";
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

    /** The mapping schema's {@code source.id}, which is also the column's width. */
    private static final Pattern SOURCE_ID = Pattern.compile("[a-z0-9-]{1,16}");

    /** The width of {@code ACKNOWLEDGED_WARNINGS}. */
    private static final int ACKNOWLEDGED_WIDTH = 1000;

    private final EntityManager entityManager;
    private final ImportIssueRepository issues;
    private final ImportRunRepository runs;
    private final AuditRecorder audit;
    private final InstantSource clock;

    DefaultImportRunService(
            EntityManager entityManager,
            ImportIssueRepository issues,
            ImportRunRepository runs,
            AuditRecorder audit,
            InstantSource clock) {
        this.entityManager = entityManager;
        this.issues = issues;
        this.runs = runs;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * What the audit trail keeps of a run request.
     *
     * @param kind the run's kind
     * @param sourceId the source it names
     */
    record Requested(String kind, String sourceId) {}

    /**
     * What the audit trail keeps of a sign-off.
     *
     * @param acknowledgedWarnings the WARNING classes acknowledged, comma-separated
     * @param scanMode the scanning mode the dry run recorded, which the load must find
     */
    record SignedOff(String acknowledgedWarnings, String scanMode) {}

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
        String acknowledged = (String) row[11];
        List<IssueCount> counts = issueCounts(id);
        ImportRun run = runs.findById(id).orElseThrow();
        boolean fatal = counts.stream().anyMatch(count -> "FATAL".equals(count.severity()));
        return new RunDetail(
                summary(row),
                (Long) row[8],
                (Long) row[6],
                (Instant) row[7],
                mode == null ? null : mode.name(),
                (Long) row[10],
                counts,
                figures,
                forecastDay,
                acknowledged == null ? List.of() : List.of(acknowledged.split(",")),
                run.getVersion(),
                run.awaitsSignOff() && !fatal);
    }

    @Override
    public void checkAccess(Actor actor) {
        allowed(actor);
    }

    @Override
    public boolean signsOff(Actor actor) {
        return actor.permissions().globalRoles().contains(GlobalRole.COMPLIANCE_ADMIN);
    }

    @Override
    @Transactional
    public long request(Actor actor, String sourceId) {
        allowed(actor);
        if (!SOURCE_ID.matcher(sourceId).matches()) {
            throw new ImportRunException(Kind.INVALID, "a source id is 1 to 16 lowercase letters, digits or hyphens");
        }
        ImportRun run = runs.save(new ImportRun(
                ImportRun.Kind.IMPORT, sourceId, actor.permissions().userId(), clock.instant()));
        record(
                actor,
                run,
                "IMPORT_RUN_REQUESTED",
                audit.diff(null, new Requested(run.getKind().name(), sourceId)));
        return run.getId();
    }

    @Override
    @Transactional
    public void signOff(Actor actor, long id, int version, Set<String> acknowledged) {
        if (!signsOff(actor)) {
            throw new ImportRunException(Kind.FORBIDDEN, "only a Compliance Admin signs an import run off");
        }
        ImportRun run =
                runs.findById(id).orElseThrow(() -> new ImportRunException(Kind.NOT_FOUND, "no such import run"));
        if (run.getVersion() != version
                || !run.awaitsSignOff()
                || issues.countByRunIdAndSeverity(id, ImportIssue.Severity.FATAL) > 0) {
            throw conflict();
        }
        List<String> warnings = issueCounts(id).stream()
                .filter(count -> "WARNING".equals(count.severity()))
                .map(IssueCount::code)
                .distinct()
                .sorted()
                .toList();
        if (!acknowledged.containsAll(warnings)) {
            throw new ImportRunException(Kind.INVALID, "every WARNING class must be acknowledged");
        }
        String codes = String.join(",", warnings);
        if (codes.length() > ACKNOWLEDGED_WIDTH) {
            throw new IllegalStateException("more WARNING classes than ACKNOWLEDGED_WARNINGS holds");
        }
        run.signedOff(actor.permissions().userId(), codes.isEmpty() ? null : codes, clock.instant());
        try {
            entityManager.flush();
        } catch (PersistenceException raced) {
            throw conflict();
        }
        record(
                actor,
                run,
                "IMPORT_RUN_SIGNED_OFF",
                audit.diff(null, new SignedOff(codes, String.valueOf(run.getScanMode()))));
    }

    private void record(Actor actor, ImportRun run, String action, AuditChanges changes) {
        audit.record(new AuditEntry(
                actor.permissions().userId(),
                actor.displayName(),
                AuditOrigin.UI,
                null,
                "IMPORT_RUN",
                run.getId(),
                action,
                changes));
    }

    private static ImportRunException conflict() {
        return new ImportRunException(Kind.CONFLICT, "the run changed or may not be signed off");
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
