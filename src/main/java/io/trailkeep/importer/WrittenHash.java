// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.closure.ClosureImportService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.crypto.SecretKey;
import org.jspecify.annotations.Nullable;

/**
 * The import ledger's written hashes (WP-26 part aa1, docs/design/importer.md §9): the keyed hash of the written forms
 * of what a ledger row's load wrote, as each module's import API reads them back from the database in the load's
 * transaction, in the order written. {@code reconcile} computes the same from the same reads, so a hash differs only if
 * a row did.
 */
final class WrittenHash {

    private WrittenHash() {}

    /**
     * A case row's hash: the case and the comments its description continued in.
     *
     * @param key {@code import.hash-key}
     * @param cases {@code casefile}'s import API
     * @param caseId the case
     * @param continued the continuation's comments, in order
     * @return the hash
     */
    static String ofCase(SecretKey key, CaseImportService cases, long caseId, List<Long> continued) {
        List<String> forms = new ArrayList<>(forms(List.of(caseId), cases.writtenCases(List.of(caseId))));
        forms.addAll(forms(continued, cases.writtenComments(continued)));
        return hash(key, forms);
    }

    /**
     * A case's imported closure's hash.
     *
     * @param key {@code import.hash-key}
     * @param closures the closure module's import API
     * @param caseId the case
     * @return the hash
     */
    static String ofClosure(SecretKey key, ClosureImportService closures, long caseId) {
        return hash(key, forms(List.of(caseId), closures.writtenClosures(List.of(caseId))));
    }

    /**
     * The hash of rows of one kind.
     *
     * @param key {@code import.hash-key}
     * @param ids the rows, in the order written
     * @param read their forms as read back, by id
     * @return the hash
     */
    static String of(SecretKey key, List<Long> ids, Map<Long, String> read) {
        return hash(key, forms(ids, read));
    }

    /**
     * A row's hash recomputed later, as {@code reconcile} does (WP-26 part aa2), from the same reads the load made: the
     * forms of the rows of one kind, then of the comments written besides them, in the order written; {@link #ofCase}
     * is {@code recomputed(key, [case], cases, continued, comments)}, {@link #of} has no comments.
     *
     * @param key {@code import.hash-key}
     * @param ids the rows of one kind
     * @param read their forms, by id
     * @param comments the comments written besides them
     * @param commentForms their forms, by id
     * @return the hash, or {@code null} when one of the rows no longer exists
     */
    static @Nullable String recomputed(
            SecretKey key,
            List<Long> ids,
            Map<Long, String> read,
            List<Long> comments,
            Map<Long, String> commentForms) {
        List<String> forms = new ArrayList<>();
        return found(forms, ids, read) && found(forms, comments, commentForms) ? hash(key, forms) : null;
    }

    private static boolean found(List<String> forms, List<Long> ids, Map<Long, String> read) {
        for (long id : ids) {
            String form = read.get(id);
            if (form == null) {
                return false;
            }
            forms.add(form);
        }
        return true;
    }

    private static List<String> forms(List<Long> ids, Map<Long, String> read) {
        return ids.stream()
                .map(id -> Objects.requireNonNull(read.get(id), "a row written in this transaction"))
                .toList();
    }

    private static String hash(SecretKey key, List<String> forms) {
        return KeyedHash.hex(key, String.join("|", forms));
    }
}
