// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.ImportIssue.Subject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.crypto.SecretKey;
import org.jspecify.annotations.Nullable;

/**
 * What one {@code reconcile} found (WP-26 part aa2, design §9): how many rows of each entity had each outcome, and each
 * difference as a {@code RECONCILE} ERROR issue carrying the row's keyed hash and its Trailkeep id, never a value. The
 * counts become the run's figures, metric {@code RECONCILE_} and the outcome, broken down by entity.
 */
final class Reconciliation {

    /** The figure that holds the verdict: 1 when the run reconciles, else 0. */
    static final String VERDICT = "RECONCILE_VERDICT";

    /** The figure, 1, that says staging was purged, so the source side could not be compared and nothing reconciles. */
    static final String STAGING_PURGED = "RECONCILE_STAGING_PURGED";

    /** The outcomes that are differences: each is an issue, and any one fails the verdict. */
    static final Set<String> DIFFERENCES = Set.of(
            "UNACCOUNTED",
            "ROW_HASH_DIFFERS",
            "WRITTEN_DIFFERS",
            "WRITTEN_MISSING",
            "CLOSURE_DIFFERS",
            "CLOSURE_MISSING");

    private static final String PREFIX = "RECONCILE_";

    private final long runId;
    private final SecretKey key;
    private final Instant at;
    private final Map<Subject, SortedMap<String, Long>> counts = new EnumMap<>(Subject.class);
    private final List<Difference> differences = new ArrayList<>();

    /**
     * One row that differs (WP-26 part aa3 writes them to the report files too).
     *
     * @param entity what the row is
     * @param code the outcome
     * @param keyHash the keyed hash of its canonical key
     * @param trailkeepId the Trailkeep row it wrote, or {@code null}
     */
    record Difference(
            Subject entity,
            String code,
            String keyHash,
            @Nullable Long trailkeepId) {}

    /**
     * An empty tally.
     *
     * @param runId the run
     * @param key {@code import.hash-key}, which hashes the keys of the rows that differ
     * @param at when the reconcile started
     */
    Reconciliation(long runId, SecretKey key, Instant at) {
        this.runId = runId;
        this.key = key;
        this.at = at;
    }

    /**
     * Counts one row's outcome; a difference is also recorded as an issue.
     *
     * @param entity what the row is
     * @param outcome what was found
     * @param legacyKey its canonical key, recorded only as its keyed hash
     * @param trailkeepId the Trailkeep row it wrote, or {@code null} when it wrote none
     */
    void found(Subject entity, String outcome, String legacyKey, @Nullable Long trailkeepId) {
        counts.computeIfAbsent(entity, any -> new TreeMap<>()).merge(outcome, 1L, Long::sum);
        if (DIFFERENCES.contains(outcome)) {
            differences.add(new Difference(entity, outcome, KeyedHash.hex(key, legacyKey), trailkeepId));
        }
    }

    long differences() {
        return differences.size();
    }

    /**
     * The rows that differ, in the order found.
     *
     * @return them
     */
    List<Difference> differing() {
        return List.copyOf(differences);
    }

    /**
     * The counts, by entity, then by outcome in code order.
     *
     * @return a copy
     */
    Map<Subject, SortedMap<String, Long>> counts() {
        Map<Subject, SortedMap<String, Long>> copy = new EnumMap<>(Subject.class);
        counts.forEach((entity, byOutcome) -> copy.put(entity, new TreeMap<>(byOutcome)));
        return copy;
    }

    /**
     * Each difference as a {@code RECONCILE} ERROR issue.
     *
     * @return the issues to save
     */
    List<ImportIssue> issues() {
        return differences.stream()
                .map(found -> new ImportIssue(
                        runId,
                        ImportIssue.Stage.RECONCILE,
                        found.entity(),
                        found.keyHash(),
                        ImportIssue.Severity.ERROR,
                        found.code(),
                        found.trailkeepId(),
                        at))
                .toList();
    }

    /**
     * The run's figures: each count, then the verdict, and the purged-staging marker when it applies.
     *
     * @param staged whether staging was there to compare with
     * @param reconciled the verdict
     * @return the figures, numbers and codes only
     */
    List<ImportRunSummary> figures(boolean staged, boolean reconciled) {
        List<ImportRunSummary> figures = new ArrayList<>();
        counts.forEach((entity, byOutcome) -> byOutcome.forEach(
                (outcome, count) -> figures.add(new ImportRunSummary(runId, PREFIX + outcome, entity.name(), count))));
        figures.add(new ImportRunSummary(runId, VERDICT, null, reconciled ? 1 : 0));
        if (!staged) {
            figures.add(new ImportRunSummary(runId, STAGING_PURGED, null, 1));
        }
        return figures;
    }
}
