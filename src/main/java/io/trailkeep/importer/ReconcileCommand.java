// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.audit.ImportedHistoryService;
import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.closure.ClosureImportService;
import io.trailkeep.document.AttachmentImportService;
import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.ImportIssue.Subject;
import io.trailkeep.importer.LoadRefusal.RefusedException;
import io.trailkeep.importer.staging.StagedAttachment;
import io.trailkeep.importer.staging.StagedCase;
import io.trailkeep.importer.staging.StagedComment;
import io.trailkeep.importer.staging.StagedEvent;
import io.trailkeep.importer.staging.StagedRow;
import io.trailkeep.importer.staging.StagingStore;
import io.trailkeep.user.ImportPeopleService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.InstantSource;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.crypto.SecretKey;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code reconcile} (WP-26 part aa2, design §3 step 6, §9): compares a finished load with its source and with
 * Trailkeep, writes nothing to the domain, and records numbers, codes and keyed hashes only.
 *
 * <ul>
 *   <li><b>Canonical</b>, while the run's staging exists: each staged row of each entity is {@code LOADED} by this run
 *       with the row hash it was staged with ({@code ROW_HASH_DIFFERS} otherwise), {@code LOADED_EARLIER} by an
 *       earlier run ({@code CHANGED_AFTER_IMPORT} when its hash differs, which the delta's dry run already reported),
 *       {@code FAILED} with an ERROR issue of its own, {@code PARENT_FAILED} when its case has one, or else {@code
 *       UNACCOUNTED};
 *   <li><b>Written</b>: every ledger row this run wrote, through {@link WrittenCheck}.
 * </ul>
 *
 * <p>Each difference ({@link Reconciliation#DIFFERENCES}) is a {@code RECONCILE} ERROR issue with the row's keyed hash
 * and Trailkeep id. The counts replace the run's {@code RECONCILE_*} figures; the verdict, {@link
 * Reconciliation#VERDICT}, is 1 and the run {@code RECONCILED} only when staging was there and nothing differs,
 * otherwise 0 and the run {@code LOADED}. It may run again, each time replacing its own issues and figures. A run that
 * is not an import run whose load finished is refused before anything is read.
 */
@Component
@Profile("importer")
class ReconcileCommand {

    /**
     * What a run found.
     *
     * @param reconciled the verdict
     * @param differences how many rows differ
     */
    record Outcome(boolean reconciled, long differences) {}

    /** Rows read per page, below Oracle's 1000 keys per {@code IN} list. */
    private static final int PAGE = 500;

    private static final Set<ImportRun.Status> FINISHED =
            EnumSet.of(ImportRun.Status.LOADED, ImportRun.Status.RECONCILED);

    private final ImportRunRepository runs;
    private final ImportIssueRepository issues;
    private final ImportRunSummaryRepository summaries;
    private final ImportLedgerRepository ledger;
    private final StagingStore staging;
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
            StagingStore staging,
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
        this.staging = staging;
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
     * @return the verdict and how many rows differ
     * @throws RefusedException {@code NOT_LOADED} for a run that is not an import run whose load finished
     */
    Outcome run(long runId) {
        ImportRun run = runs.findById(runId).orElseThrow(() -> new IllegalStateException("No import run " + runId));
        refuseUnlessFinished(run);
        SecretKey key = properties.hashKeySpec();
        Reconciliation found = new Reconciliation(runId, key, clock.instant());
        boolean staged = run.getStagingPurgedAt() == null;
        if (staged) {
            canonical(run, Subject.CASE, StagedCase.class, key, found);
            canonical(run, Subject.COMMENT, StagedComment.class, key, found);
            canonical(run, Subject.ATTACHMENT, StagedAttachment.class, key, found);
            canonical(run, Subject.EVENT, StagedEvent.class, key, found);
        }
        WrittenCheck written = new WrittenCheck(
                cases, closures, attachments, history, key, people.principals().importer());
        long after = 0;
        do {
            after = page(runId, after, written, found);
        } while (after > 0);
        boolean reconciled = staged && found.differences() == 0;
        transactions.executeWithoutResult(status -> {
            ImportRun current = runs.findById(runId).orElseThrow();
            refuseUnlessFinished(current);
            issues.deleteByRunIdAndStage(runId, ImportIssue.Stage.RECONCILE);
            summaries.deleteReconcileFigures(runId);
            found.issues().forEach(issues::save);
            found.figures(staged, reconciled).forEach(summaries::save);
            current.loadMoved(reconciled ? ImportRun.Status.RECONCILED : ImportRun.Status.LOADED, 0, clock.instant());
            runs.save(current);
        });
        return new Outcome(reconciled, found.differences());
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

    /** The staged rows of one entity against the ledger and the run's issues, a page at a time. */
    private <T extends StagedRow> void canonical(
            ImportRun run, Subject subject, Class<T> type, SecretKey key, Reconciliation found) {
        long runId = run.getId();
        @Nullable String after = null;
        for (List<T> page = staging.page(type, runId, null, PAGE);
                !page.isEmpty();
                page = staging.page(type, runId, after, PAGE)) {
            Map<String, ImportLedgerEntry> held = ledger
                    .findBySourceIdAndEntityAndLegacyKeyIn(
                            run.getSourceId(),
                            subject,
                            page.stream().map(row -> row.getKey().legacyKey()).toList())
                    .stream()
                    .collect(Collectors.toMap(ImportLedgerEntry::getLegacyKey, Function.identity()));
            Set<String> failed =
                    failed(runId, subject, page.stream().map(row -> row.getKey().legacyKey()), key);
            Set<String> parentFailed = failed(
                    runId,
                    Subject.CASE,
                    page.stream().map(ReconcileCommand::caseKey).filter(Objects::nonNull),
                    key);
            for (T row : page) {
                found.found(
                        subject,
                        outcome(row, held, failed, parentFailed, key, runId),
                        row.getKey().legacyKey(),
                        heldId(held.get(row.getKey().legacyKey())));
            }
            after = page.getLast().getKey().legacyKey();
        }
    }

    /** The keyed hashes of those of these keys whose rows have an ERROR issue in this run, at any stage. */
    private Set<String> failed(long runId, Subject subject, Stream<String> keys, SecretKey key) {
        Map<String, String> hashes = new HashMap<>();
        keys.forEach(legacyKey -> hashes.put(KeyedHash.hex(key, legacyKey), legacyKey));
        return hashes.isEmpty()
                ? Set.of()
                : Set.copyOf(issues.keyHashesWith(runId, subject, Severity.ERROR, hashes.keySet()));
    }

    private static String outcome(
            StagedRow row,
            Map<String, ImportLedgerEntry> held,
            Set<String> failed,
            Set<String> parentFailed,
            SecretKey key,
            long runId) {
        ImportLedgerEntry loaded = held.get(row.getKey().legacyKey());
        if (loaded != null) {
            // Constant time, like every hash comparison here (SpotBugs UNSAFE_HASH_EQUALS).
            boolean same = MessageDigest.isEqual(
                    row.getRowHash().getBytes(StandardCharsets.US_ASCII),
                    loaded.getRowHash().getBytes(StandardCharsets.US_ASCII));
            if (loaded.getRunId() == runId) {
                return same ? "LOADED" : "ROW_HASH_DIFFERS";
            }
            return same ? "LOADED_EARLIER" : "CHANGED_AFTER_IMPORT";
        }
        if (failed.contains(KeyedHash.hex(key, row.getKey().legacyKey()))) {
            return "FAILED";
        }
        String caseKey = caseKey(row);
        return caseKey != null && parentFailed.contains(KeyedHash.hex(key, caseKey)) ? "PARENT_FAILED" : "UNACCOUNTED";
    }

    private static @Nullable Long heldId(@Nullable ImportLedgerEntry loaded) {
        return loaded == null ? null : loaded.getTargetId();
    }

    /** A child's case key; {@code null} for a case. */
    private static @Nullable String caseKey(StagedRow row) {
        return switch (row) {
            case StagedComment comment -> comment.getCaseKey();
            case StagedAttachment attachment -> attachment.getCaseKey();
            case StagedEvent event -> event.getCaseKey();
            default -> null;
        };
    }
}
