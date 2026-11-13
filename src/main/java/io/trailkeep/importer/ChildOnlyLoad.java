// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.casefile.CaseImportService.Created;
import io.trailkeep.document.AttachmentRejectedException;
import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.ImportIssue.Subject;
import io.trailkeep.importer.staging.StagedRow;
import io.trailkeep.shared.DomainException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Limit;

/**
 * The child-only part of a delta's load (WP-26 part y2, design §3.1, §8.2): new comments, attachments and history
 * events of cases an earlier run loaded, staged by this run whether or not it staged the case again, are written onto
 * the existing Trailkeep case, found through the import ledger. The cases are read from the ledger in pages, in key
 * order; a child the ledger holds is never written again (a changed one is the dry run's {@code
 * CHANGED_AFTER_IMPORT}), and one with an ERROR is left out as on a first load. Each case's new children are written in
 * one transaction with their ledger rows, search text and audit events, attachments scanned before it ({@link
 * ChildLoads}); the run's count of loaded cases does not move. A case whose children cannot be written now (an event
 * failing its rules, a changed file, a comment the import API refuses) is a {@code LOAD} issue on the case's keyed
 * hash, as on a first load, which the next load drops and tries again. An attachment a first load left out without a
 * ledger row is staged again by a delta and tried again here.
 */
final class ChildOnlyLoad {

    /** Ledger rows read per page, and keys per {@code IN} list, below Oracle's 1000. */
    private static final int PAGE = 500;

    /**
     * What the child-only load did.
     *
     * @param cases the loaded cases it wrote new children onto
     * @param refused those it refused now as {@code LOAD} issues
     */
    record Extended(long cases, long refused) {}

    private final ChildLoads children;
    private final CommentLoad.Context context;

    ChildOnlyLoad(ChildLoads children) {
        this.children = children;
        this.context = children.comments().context();
    }

    /**
     * Writes the new children of every case earlier runs of the source loaded.
     *
     * @param lock the load's hold on its run
     * @param people the load's matched people
     * @param importer the "Trailkeep import" principal
     * @return what it did
     * @throws io.trailkeep.document.ScannerUnavailableException when the scanner gives no verdict
     */
    Extended load(LoadRunLock lock, PeopleMatcher people, CaseComments.Author importer) {
        ImportLedgerRepository ledger = context.ledger();
        String sourceId = context.run().sourceId();
        long runId = context.run().runId();
        long cases = 0;
        long refused = 0;
        List<ImportLedgerEntry> page = ledger.findBySourceIdAndEntityAndRunIdNotOrderByLegacyKeyAsc(
                sourceId, Subject.CASE, runId, Limit.of(PAGE));
        while (!page.isEmpty()) {
            Map<String, ChildLoads.Staged> fresh = unloaded(children.staged(
                    page.stream().map(ImportLedgerEntry::getLegacyKey).toList()));
            Map<Long, Created> existing = context.cases()
                    .existing(page.stream()
                            .filter(entry -> fresh.containsKey(entry.getLegacyKey()))
                            .map(ImportLedgerEntry::getCaseId)
                            .toList());
            for (ImportLedgerEntry entry : page) {
                ChildLoads.Staged staged = fresh.get(entry.getLegacyKey());
                if (staged == null) {
                    continue;
                }
                Created created = Objects.requireNonNull(existing.get(entry.getCaseId()), "a ledger case exists");
                String refusal = extend(lock, created, staged, people, importer);
                if (refusal == null) {
                    cases++;
                    continue;
                }
                ImportIssue issue = new ImportIssue(
                        runId,
                        ImportIssue.Stage.LOAD,
                        Subject.CASE,
                        KeyedHash.hex(context.hashKey(), entry.getLegacyKey()),
                        Severity.ERROR,
                        refusal,
                        null,
                        context.now());
                lock.write(0, () -> context.issues().save(issue));
                refused++;
            }
            page = ledger.findBySourceIdAndEntityAndRunIdNotAndLegacyKeyGreaterThanOrderByLegacyKeyAsc(
                    sourceId, Subject.CASE, runId, page.getLast().getLegacyKey(), Limit.of(PAGE));
        }
        return new Extended(cases, refused);
    }

    /** Writes one case's new children in one transaction; {@code null} once written, else what refused them. */
    private @Nullable String extend(
            LoadRunLock lock,
            Created created,
            ChildLoads.Staged staged,
            PeopleMatcher people,
            CaseComments.Author importer) {
        String refusal = children.events().refusal(staged.events());
        if (refusal != null) {
            return refusal;
        }
        List<CaseComments.Comment> texts = children.comments().texts().of(staged.comments(), people);
        AttachmentLoad.Scanned scanned = children.attachments().scan(staged.files(), lock);
        if (scanned.refusal() != null) {
            return scanned.refusal();
        }
        try {
            lock.write(0, () -> children.write(created, texts, scanned, staged, people, importer));
        } catch (AttachmentRejectedException changed) {
            return AttachmentLoad.CONTENT_CHANGED;
        } catch (DomainException invalid) {
            if (invalid.kind() != DomainException.Kind.INVALID) {
                throw invalid;
            }
            return LoadCommand.CASE_REFUSED;
        }
        return null;
    }

    /** The staged children the ledger does not hold yet, of the cases that have any. */
    private Map<String, ChildLoads.Staged> unloaded(Map<String, ChildLoads.Staged> staged) {
        Set<String> comments =
                loaded(Subject.COMMENT, staged.values().stream().flatMap(one -> one.comments().stream()));
        Set<String> files = loaded(Subject.ATTACHMENT, staged.values().stream().flatMap(one -> one.files().stream()));
        Set<String> events = loaded(Subject.EVENT, staged.values().stream().flatMap(one -> one.events().stream()));
        Map<String, ChildLoads.Staged> fresh = new HashMap<>();
        staged.forEach((key, one) -> {
            ChildLoads.Staged left = new ChildLoads.Staged(
                    without(one.comments(), comments), without(one.files(), files), without(one.events(), events));
            if (!left.comments().isEmpty()
                    || !left.files().isEmpty()
                    || !left.events().isEmpty()) {
                fresh.put(key, left);
            }
        });
        return fresh;
    }

    /** The keys of these rows of one entity that the ledger holds for the source. */
    private Set<String> loaded(Subject subject, Stream<? extends StagedRow> rows) {
        List<String> keys = rows.map(row -> row.getKey().legacyKey()).toList();
        Set<String> loaded = new HashSet<>();
        for (int from = 0; from < keys.size(); from += PAGE) {
            loaded.addAll(context.ledger()
                    .loaded(context.run().sourceId(), subject, keys.subList(from, Math.min(keys.size(), from + PAGE))));
        }
        return loaded;
    }

    private static <T extends StagedRow> List<T> without(List<T> rows, Set<String> loaded) {
        return rows.stream()
                .filter(row -> !loaded.contains(row.getKey().legacyKey()))
                .toList();
    }
}
