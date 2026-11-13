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

    /** The outcomes that are differences: each is an issue, and any one fails the verdict. */
    static final Set<String> DIFFERENCES =
            Set.of("WRITTEN_DIFFERS", "WRITTEN_MISSING", "CLOSURE_DIFFERS", "CLOSURE_MISSING");

    private static final String PREFIX = "RECONCILE_";

    private final long runId;
    private final SecretKey key;
    private final Instant at;
    private final Map<Subject, SortedMap<String, Long>> counts = new EnumMap<>(Subject.class);
    private final List<ImportIssue> issues = new ArrayList<>();

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
            issues.add(new ImportIssue(
                    runId,
                    ImportIssue.Stage.RECONCILE,
                    entity,
                    KeyedHash.hex(key, legacyKey),
                    ImportIssue.Severity.ERROR,
                    outcome,
                    trailkeepId,
                    at));
        }
    }

    long differences() {
        return issues.size();
    }

    List<ImportIssue> issues() {
        return List.copyOf(issues);
    }

    /**
     * The run's figures, one per entity and outcome.
     *
     * @return the figures, numbers and codes only
     */
    List<ImportRunSummary> figures() {
        List<ImportRunSummary> figures = new ArrayList<>();
        counts.forEach((entity, byOutcome) -> byOutcome.forEach(
                (outcome, count) -> figures.add(new ImportRunSummary(runId, PREFIX + outcome, entity.name(), count))));
        return figures;
    }
}
