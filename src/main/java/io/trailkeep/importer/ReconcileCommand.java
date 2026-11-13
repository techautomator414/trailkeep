// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.audit.ImportedHistoryService;
import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.closure.ClosureImportService;
import io.trailkeep.document.AttachmentImportService;
import io.trailkeep.importer.LoadRefusal.RefusedException;
import io.trailkeep.user.ImportPeopleService;
import java.time.InstantSource;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.crypto.SecretKey;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code reconcile} (WP-26 part aa2a, design §3 step 6, §9): compares what a finished load wrote with Trailkeep as it
 * is now, writes nothing to the domain, and records numbers, codes and keyed hashes only. Every ledger row this run
 * wrote is compared through {@link WrittenCheck}; the comparison with the source (staging) and the verdict follow in
 * part aa2b. Each difference ({@link Reconciliation#DIFFERENCES}) is a {@code RECONCILE} ERROR issue with the row's
 * keyed hash and Trailkeep id, and the counts replace the run's {@code RECONCILE_*} figures. It may run again, each
 * time replacing its own issues and figures. A run that is not an import run whose load finished is refused before
 * anything is read.
 */
@Component
@Profile("importer")
class ReconcileCommand {

    /**
     * What a run found.
     *
     * @param differences how many rows differ
     */
    record Outcome(long differences) {}

    /** Rows read per page, below Oracle's 1000 keys per {@code IN} list. */
    private static final int PAGE = 500;

    private static final Set<ImportRun.Status> FINISHED =
            EnumSet.of(ImportRun.Status.LOADED, ImportRun.Status.RECONCILED);

    private final ImportRunRepository runs;
    private final ImportIssueRepository issues;
    private final ImportRunSummaryRepository summaries;
    private final ImportLedgerRepository ledger;
    private final TransactionTemplate transactions;
    private final CaseImportService cases;
    private final ClosureImportService closures;
    private final AttachmentImportService attachments;
    private final ImportedHistoryService history;
    private final ImportPeopleService people;
    private final ImportProperties properties;
    private final InstantSource clock;

    ReconcileCommand(
            ImportRunRepository runs,
            ImportIssueRepository issues,
            ImportRunSummaryRepository summaries,
            ImportLedgerRepository ledger,
            TransactionTemplate transactions,
            CaseImportService cases,
            ClosureImportService closures,
            AttachmentImportService attachments,
            ImportedHistoryService history,
            ImportPeopleService people,
            ImportProperties properties,
            InstantSource clock) {
        this.runs = runs;
        this.issues = issues;
        this.summaries = summaries;
        this.ledger = ledger;
        this.transactions = transactions;
        this.cases = cases;
        this.closures = closures;
        this.attachments = attachments;
        this.history = history;
        this.people = people;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Reconciles a run whose load finished.
     *
     * @param runId the run
     * @return how many rows differ
     * @throws RefusedException {@code NOT_LOADED} for a run that is not an import run whose load finished
     */
    Outcome run(long runId) {
        ImportRun run = runs.findById(runId).orElseThrow(() -> new IllegalStateException("No import run " + runId));
        refuseUnlessFinished(run);
        SecretKey key = properties.hashKeySpec();
        Reconciliation found = new Reconciliation(runId, key, clock.instant());
        WrittenCheck written = new WrittenCheck(
                cases, closures, attachments, history, key, people.principals().importer());
        long after = 0;
        do {
            after = page(runId, after, written, found);
        } while (after > 0);
        transactions.executeWithoutResult(status -> {
            ImportRun current = runs.findById(runId).orElseThrow();
            refuseUnlessFinished(current);
            issues.deleteByRunIdAndStage(runId, ImportIssue.Stage.RECONCILE);
            summaries.deleteReconcileFigures(runId);
            found.issues().forEach(issues::save);
            found.figures().forEach(summaries::save);
        });
        return new Outcome(found.differences());
    }

    private static void refuseUnlessFinished(ImportRun run) {
        if (run.getKind() != ImportRun.Kind.IMPORT || !FINISHED.contains(run.getStatus())) {
            throw new RefusedException(run.getId(), LoadRefusal.NOT_LOADED);
        }
    }

    /** One page of the run's ledger rows compared in a transaction of its own; its last id, or 0 after the last. */
    private long page(long runId, long after, WrittenCheck written, Reconciliation found) {
        return Objects.requireNonNull(transactions.execute(status -> {
            List<ImportLedgerEntry> page = ledger.findByRunIdAndIdGreaterThanOrderByIdAsc(runId, after, Limit.of(PAGE));
            if (page.isEmpty()) {
                return 0L;
            }
            written.page(page, found);
            return page.getLast().getId();
        }));
    }
}
