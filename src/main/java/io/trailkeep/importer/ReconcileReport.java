// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.Reconciliation.Difference;
import io.trailkeep.shared.csv.CsvFormat;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code reconcile}'s report files (WP-26 part aa3, design §9): {@code reconcile.csv} and {@code reconcile.html} in
 * the run's report folder ({@link ReportFiles}). They hold what the run's figures and issues hold, the counts per
 * entity and outcome, the verdict and each difference, and nothing else: codes, numbers, keyed hashes and Trailkeep
 * ids, never a legacy key or value. The CSV is written through {@link CsvFormat} (quoted, formula cells defused, BOM).
 */
final class ReconcileReport {

    private ReconcileReport() {}

    /**
     * Writes both files, replacing those of an earlier reconcile of the run.
     *
     * @param folder the run's report folder, from {@link ReportFiles#folder}
     * @param runId the run
     * @param found what the reconcile found
     * @param staged whether staging was there to compare with
     * @param reconciled the verdict
     */
    static void write(Path folder, long runId, Reconciliation found, boolean staged, boolean reconciled) {
        ReportFiles.file(folder, "reconcile.csv", CsvFormat.BOM + csv(found, staged, reconciled));
        ReportFiles.file(folder, "reconcile.html", html(runId, found, staged, reconciled));
    }

    /** One row per count, the verdict, then one row per difference. */
    static String csv(Reconciliation found, boolean staged, boolean reconciled) {
        StringBuilder csv = new StringBuilder(
                CsvFormat.line(List.of("row", "entity", "outcome", "rows", "key_hash", "trailkeep_id")));
        found.counts()
                .forEach((entity, byOutcome) -> byOutcome.forEach((outcome, count) -> csv.append(
                        CsvFormat.line(List.of("count", entity.name(), outcome, Long.toString(count), "", "")))));
        csv.append(CsvFormat.line(List.of("verdict", "", verdict(staged, reconciled), "", "", "")));
        for (Difference difference : found.differing()) {
            csv.append(CsvFormat.line(List.of(
                    "difference",
                    difference.entity().name(),
                    difference.code(),
                    "",
                    difference.keyHash(),
                    id(difference.trailkeepId()))));
        }
        return csv.toString();
    }

    /** The page: the verdict, a table of counts and a table of differences. */
    static String html(long runId, Reconciliation found, boolean staged, boolean reconciled) {
        List<List<String>> counts = new ArrayList<>();
        found.counts()
                .forEach((entity, byOutcome) -> byOutcome.forEach(
                        (outcome, count) -> counts.add(List.of(entity.name(), outcome, Long.toString(count)))));
        List<List<String>> differences = found.differing().stream()
                .map(row -> List.of(row.entity().name(), row.code(), row.keyHash(), id(row.trailkeepId())))
                .toList();
        return ReportFiles.page(
                "Reconciliation of import run " + runId,
                "Verdict: " + verdict(staged, reconciled) + ". Keyed hashes and Trailkeep ids only; no legacy key or"
                        + " value.",
                List.of(
                        ReportFiles.table("Rows per entity and outcome", List.of("Entity", "Outcome", "Rows"), counts),
                        ReportFiles.table(
                                "Rows that differ",
                                List.of("Entity", "Code", "Keyed hash", "Trailkeep id"),
                                differences)));
    }

    private static String verdict(boolean staged, boolean reconciled) {
        return reconciled ? "RECONCILED" : staged ? "NOT_RECONCILED" : "NOT_RECONCILED (staging purged)";
    }

    private static String id(@Nullable Long trailkeepId) {
        return trailkeepId == null ? "" : Long.toString(trailkeepId);
    }
}
