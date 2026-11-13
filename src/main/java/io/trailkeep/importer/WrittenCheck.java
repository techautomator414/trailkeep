// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.audit.ImportedHistoryService;
import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.casefile.CaseImportService.Existing;
import io.trailkeep.closure.ClosureImportService;
import io.trailkeep.document.AttachmentImportService;
import io.trailkeep.importer.ImportIssue.Subject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.crypto.SecretKey;
import org.jspecify.annotations.Nullable;

/**
 * {@code reconcile}'s written comparison (WP-26 part aa2, design §9): each ledger row's written hash recomputed from
 * Trailkeep through the import APIs' reads that the load hashed, in the caller's transaction, a page of rows at a time.
 * Outcomes, by the row's entity:
 *
 * <ul>
 *   <li>{@code WRITTEN_MATCH}, or the differences {@code WRITTEN_DIFFERS} and {@code WRITTEN_MISSING} (a row it wrote
 *       is gone from a case neither archived nor purged); a closed case's imported closure likewise {@code CLOSURE_*};
 *   <li>{@code CASE_ARCHIVED} and {@code CASE_PURGED}: the row's case was archived or purged since, which changes or
 *       removes what was written; skipped, never a difference;
 *   <li>{@code WRITTEN_HASH_MISSING}: loaded before V19 recorded written hashes; skipped, never a difference;
 *   <li>a case changed since its load by anyone (N-i) is compared by its canonical hash only: {@code RELINKED} when
 *       {@code relink-owners} changed it last, as the import principal, else {@code CHANGED_IN_TRAILKEEP}. Neither is a
 *       difference. Its insert-only rows (comments, attachments, history, its imported closure) are still compared.
 * </ul>
 */
final class WrittenCheck {

    private final CaseImportService cases;
    private final ClosureImportService closures;
    private final AttachmentImportService attachments;
    private final ImportedHistoryService history;
    private final SecretKey key;
    private final long importer;

    /**
     * The comparison of one reconcile.
     *
     * @param cases {@code casefile}'s import API
     * @param closures the closure module's import API
     * @param attachments the document module's import API
     * @param history the audit module's import API
     * @param key {@code import.hash-key}
     * @param importer the import principal's user id, which only {@code relink-owners} changes cases as after a load
     */
    WrittenCheck(
            CaseImportService cases,
            ClosureImportService closures,
            AttachmentImportService attachments,
            ImportedHistoryService history,
            SecretKey key,
            long importer) {
        this.cases = cases;
        this.closures = closures;
        this.attachments = attachments;
        this.history = history;
        this.key = key;
        this.importer = importer;
    }

    /** The written forms of a page's rows, as read back now, by id per kind; closures by case id. */
    private record Forms(
            Map<Long, String> cases,
            Map<Long, String> comments,
            Map<Long, String> attachments,
            Map<Long, String> events,
            Map<Long, String> closures) {

        Map<Long, String> of(Subject entity) {
            return switch (entity) {
                case COMMENT -> comments;
                case ATTACHMENT -> attachments;
                case EVENT -> events;
                default -> cases;
            };
        }
    }

    /**
     * Compares a page of ledger rows, in the caller's transaction.
     *
     * @param page the rows
     * @param found the tally to count into
     */
    void page(List<ImportLedgerEntry> page, Reconciliation found) {
        Map<Long, Existing> states = cases.existing(
                page.stream().map(ImportLedgerEntry::getCaseId).distinct().toList());
        Forms forms = read(page);
        for (ImportLedgerEntry row : page) {
            row(row, states.get(row.getCaseId()), forms, found);
        }
    }

    private Forms read(List<ImportLedgerEntry> page) {
        Map<Subject, List<Long>> ids = new EnumMap<>(Subject.class);
        List<Long> closed = new ArrayList<>();
        for (ImportLedgerEntry row : page) {
            Subject entity = row.getEntity();
            ids.computeIfAbsent(entity, any -> new ArrayList<>()).add(row.getTargetId());
            ids.computeIfAbsent(entity == Subject.CASE ? Subject.COMMENT : entity, any -> new ArrayList<>())
                    .addAll(row.getParts());
            if (row.getClosureHash() != null) {
                closed.add(row.getCaseId());
            }
        }
        return new Forms(
                cases.writtenCases(ids.getOrDefault(Subject.CASE, List.of())),
                cases.writtenComments(ids.getOrDefault(Subject.COMMENT, List.of())),
                attachments.writtenAttachments(ids.getOrDefault(Subject.ATTACHMENT, List.of())),
                history.writtenEvents(ids.getOrDefault(Subject.EVENT, List.of())),
                closures.writtenClosures(closed));
    }

    private void row(ImportLedgerEntry row, @Nullable Existing state, Forms forms, Reconciliation found) {
        Subject entity = row.getEntity();
        String written = row.getWrittenHash();
        String skipped = skipped(state, written);
        if (state == null || written == null || skipped != null) {
            found.found(entity, Objects.requireNonNull(skipped), row.getLegacyKey(), row.getTargetId());
            return;
        }
        Long changedBy = state.changedBy();
        if (entity == Subject.CASE && changedBy != null) {
            String outcome = changedBy == importer ? "RELINKED" : "CHANGED_IN_TRAILKEEP";
            found.found(entity, outcome, row.getLegacyKey(), row.getTargetId());
        } else if (entity == Subject.CASE) {
            compare(
                    "WRITTEN_",
                    written,
                    WrittenHash.recomputed(
                            key, List.of(row.getTargetId()), forms.cases(), row.getParts(), forms.comments()),
                    row,
                    found);
        } else {
            List<Long> ids = new ArrayList<>(List.of(row.getTargetId()));
            ids.addAll(row.getParts());
            compare(
                    "WRITTEN_",
                    written,
                    WrittenHash.recomputed(key, ids, forms.of(entity), List.of(), Map.of()),
                    row,
                    found);
        }
        String closure = row.getClosureHash();
        if (closure != null) {
            compare(
                    "CLOSURE_",
                    closure,
                    WrittenHash.recomputed(key, List.of(row.getCaseId()), forms.closures(), List.of(), Map.of()),
                    row,
                    found);
        }
    }

    /** Why a row is not compared, or {@code null} when it is; a case that no longer exists is a difference. */
    private static @Nullable String skipped(@Nullable Existing state, @Nullable String written) {
        if (state == null) {
            return "WRITTEN_MISSING";
        }
        if (state.state() != Existing.State.ACTIVE) {
            return "CASE_" + state.state().name();
        }
        return written == null ? "WRITTEN_HASH_MISSING" : null;
    }

    private static void compare(
            String what, String recorded, @Nullable String recomputed, ImportLedgerEntry row, Reconciliation found) {
        // Constant time, like every hash comparison here (SpotBugs UNSAFE_HASH_EQUALS).
        String outcome = recomputed == null
                ? "MISSING"
                : MessageDigest.isEqual(
                                recorded.getBytes(StandardCharsets.US_ASCII),
                                Objects.requireNonNull(recomputed).getBytes(StandardCharsets.US_ASCII))
                        ? "MATCH"
                        : "DIFFERS";
        found.found(row.getEntity(), what + outcome, row.getLegacyKey(), row.getTargetId());
    }
}
