// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.casefile.CaseImportService.ImportedComment;
import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.ImportIssue.Subject;
import io.trailkeep.importer.ImportLedgerEntry.Loaded;
import io.trailkeep.importer.staging.StagedComment;
import io.trailkeep.importer.staging.StagedRow;
import io.trailkeep.importer.staging.StagingStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.crypto.SecretKey;

/**
 * The comment part of one load (WP-26 part u, design §7.2, §8.2): reads the staged comments of a page of cases, leaving
 * out every comment with an ERROR from the extract or the dry run (skipped by rule or not), and writes a case's
 * comments ({@link CaseComments}) through {@code casefile}'s comment import API, in the case's transaction, each staged
 * comment with its import ledger row.
 */
final class CommentLoad {

    /** Keyed hashes per {@code IN} list, well below Oracle's 1000. */
    private static final int HASHES_PER_QUERY = 500;

    /** A case's comments in the order they were written, then by key. */
    private static final Comparator<StagedComment> WRITTEN = Comparator.comparing(
                    StagedComment::getWrittenAt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()))
            .thenComparing(row -> row.getKey().legacyKey());

    /**
     * What a load's comments need.
     *
     * @param staging the staged rows
     * @param issues the run's issues
     * @param ledger the import ledger
     * @param cases {@code casefile}'s import API
     * @param run the run, its source and mapping hash
     * @param audited the run as its audit entries name it
     * @param hashKey the key that hashes each row's key into its issues
     * @param now the load's time
     * @param texts the mapping's description and comment rules
     */
    record Context(
            StagingStore staging,
            ImportIssueRepository issues,
            ImportLedgerRepository ledger,
            CaseImportService cases,
            Loaded run,
            CaseImportService.ImportRun audited,
            SecretKey hashKey,
            Instant now,
            CaseComments texts) {}

    private final Context context;

    CommentLoad(Context context) {
        this.context = context;
    }

    /**
     * The mapping's rules for a case's description and comments.
     *
     * @return them
     */
    CaseComments texts() {
        return context.texts();
    }

    /**
     * The staged comments of a page's cases to load, by case key, in the order they were written.
     *
     * @param caseKeys the cases' canonical keys
     * @return each case's comments; a case without any is absent
     */
    Map<String, List<StagedComment>> staged(List<String> caseKeys) {
        Map<String, List<StagedComment>> byCase =
                loadable(context, StagedComment.class, Subject.COMMENT, StagedComment::getCaseKey, caseKeys);
        byCase.values().forEach(rows -> rows.sort(WRITTEN));
        return byCase;
    }

    /**
     * The staged children of one entity of a page's cases, by case key, in key order, leaving out every one with an
     * ERROR from the extract or the dry run (skipped by rule or not); comments and attachments alike.
     *
     * @param <T> the entity's row type
     * @param context the load
     * @param type the entity's row type
     * @param subject the entity, as its issues name it
     * @param caseKey a row's case key
     * @param caseKeys the cases' canonical keys
     * @return each case's children; a case without any is absent
     */
    static <T extends StagedRow> Map<String, List<T>> loadable(
            Context context, Class<T> type, Subject subject, Function<T, String> caseKey, List<String> caseKeys) {
        long runId = context.run().runId();
        List<T> staged = context.staging().children(type, runId, caseKeys);
        List<String> hashes = staged.stream()
                .map(row -> KeyedHash.hex(context.hashKey(), row.getKey().legacyKey()))
                .toList();
        Set<String> failed = new HashSet<>();
        for (int from = 0; from < hashes.size(); from += HASHES_PER_QUERY) {
            List<String> some = hashes.subList(from, Math.min(hashes.size(), from + HASHES_PER_QUERY));
            failed.addAll(context.issues().keyHashesWith(runId, subject, Severity.ERROR, some));
        }
        Map<String, List<T>> byCase = new HashMap<>();
        for (int i = 0; i < staged.size(); i++) {
            if (!failed.contains(hashes.get(i))) {
                byCase.computeIfAbsent(caseKey.apply(staged.get(i)), key -> new ArrayList<>())
                        .add(staged.get(i));
            }
        }
        return byCase;
    }

    /**
     * Writes one comment of a case being loaded, in the case's transaction: each of its parts, and its ledger row
     * unless it continues the description, which the case's own row covers.
     *
     * @param created the case
     * @param comment the comment
     */
    void write(CaseImportService.Created created, CaseComments.Comment comment) {
        long first = 0;
        for (String part : comment.parts()) {
            long id = context.cases()
                    .importComment(
                            context.audited(),
                            new ImportedComment(
                                    created.number(),
                                    part,
                                    comment.author().id(),
                                    comment.author().name(),
                                    comment.writtenAt()));
            first = first == 0 ? id : first;
        }
        StagedComment staged = comment.staged();
        if (staged != null) {
            context.ledger()
                    .save(ImportLedgerEntry.ofChild(
                            context.run(),
                            Subject.COMMENT,
                            staged.getKey().legacyKey(),
                            staged.getRowHash(),
                            created.caseId(),
                            first,
                            context.now()));
        }
    }
}
