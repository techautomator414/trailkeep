// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.shared.csv.CsvFormat;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code dry-run}'s preview report files (WP-26 part ab2, design §9): {@code dry-run.csv} and {@code dry-run.html} in
 * the run's report folder ({@link ReportFiles}), for the operator to hand to the approver. They hold what the Import
 * runs screen renders and the issues behind it, and nothing else: every figure of {@code IMPORT_RUN_SUMMARY} (a metric,
 * a dimension that is a code, a status or a Trailkeep id, and a number) and every issue of the run as its entity,
 * stage, severity, code, keyed hash and numeric detail, never a legacy key or value. The CSV is written through {@link
 * CsvFormat}.
 */
final class DryRunReport {

    private DryRunReport() {}

    /**
     * Writes both files, replacing those of an earlier dry run of the run.
     *
     * @param folder the run's report folder, from {@link ReportFiles#folder}
     * @param runId the run
     * @param figures the run's figures, each its metric, dimension and value
     * @param issues the run's issues, each its stage, entity, severity, code, keyed hash and detail
     */
    static void write(Path folder, long runId, List<Object[]> figures, List<Object[]> issues) {
        List<List<String>> figureRows = texts(figures);
        List<List<String>> issueRows = texts(issues).stream()
                .map(issue ->
                        List.of(issue.get(1), issue.get(0), issue.get(2), issue.get(3), issue.get(4), issue.get(5)))
                .toList();
        ReportFiles.file(folder, "dry-run.csv", CsvFormat.BOM + csv(figureRows, issueRows));
        ReportFiles.file(folder, "dry-run.html", html(runId, figureRows, issueRows));
    }

    /**
     * One row per figure, then one per issue.
     *
     * @param figures metric, dimension, value
     * @param issues entity, stage, severity, code, keyed hash, detail
     * @return the CSV, without its BOM
     */
    static String csv(List<List<String>> figures, List<List<String>> issues) {
        StringBuilder csv = new StringBuilder(CsvFormat.line(List.of(
                "row", "metric", "dimension", "value", "entity", "stage", "severity", "code", "key_hash", "detail")));
        for (List<String> figure : figures) {
            List<String> cells = new ArrayList<>(List.of("figure"));
            cells.addAll(figure);
            cells.addAll(List.of("", "", "", "", "", ""));
            csv.append(CsvFormat.line(cells));
        }
        for (List<String> issue : issues) {
            List<String> cells = new ArrayList<>(List.of("issue", "", "", ""));
            cells.addAll(issue);
            csv.append(CsvFormat.line(cells));
        }
        return csv.toString();
    }

    /**
     * The page: a table of figures and a table of issues.
     *
     * @param runId the run
     * @param figures metric, dimension, value
     * @param issues entity, stage, severity, code, keyed hash, detail
     * @return the page
     */
    static String html(long runId, List<List<String>> figures, List<List<String>> issues) {
        return ReportFiles.page(
                "Dry run of import run " + runId,
                "The figures the sign-off reads and every issue of the run, by code, keyed hash and number only; no"
                        + " legacy key or value.",
                List.of(
                        ReportFiles.table("Figures", List.of("Metric", "Dimension", "Value"), figures),
                        ReportFiles.table(
                                "Issues",
                                List.of("Entity", "Stage", "Severity", "Code", "Keyed hash", "Detail"),
                                issues)));
    }

    private static List<List<String>> texts(List<Object[]> rows) {
        return rows.stream()
                .map(row -> {
                    List<String> cells = new ArrayList<>();
                    for (Object cell : row) {
                        cells.add(text(cell));
                    }
                    return List.copyOf(cells);
                })
                .toList();
    }

    private static String text(@Nullable Object cell) {
        return cell == null ? "" : cell instanceof Enum<?> constant ? constant.name() : cell.toString();
    }
}
