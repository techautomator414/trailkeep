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
 * a dimension that is a code, a status or a Trailkeep id, and a number) and the run's issues as entity, stage,
 * severity, code, keyed hash and numeric detail, never a legacy key or value.
 *
 * <p>Memory does not grow with the number of issues: the CSV, through {@link CsvFormat}, lists every issue, read a
 * page at a time by id and written line by line; the page shows the issue counts per entity, stage, severity and code
 * and only the first {@link ReportFiles#PAGE_ROWS} issues, saying so.
 */
final class DryRunReport {

    /** Issues read per page for the CSV. */
    static final int PAGE = 5_000;

    /** One page of a run's issues. */
    @FunctionalInterface
    interface IssuePages {

        /**
         * The run's next issues by id.
         *
         * @param afterId the last id already read, or 0 for the first page
         * @param limit at most this many
         * @return id, stage, entity, severity, code, keyed hash and detail of each, in id order
         */
        List<Object[]> after(long afterId, int limit);
    }

    private DryRunReport() {}

    /**
     * Writes both files, replacing those of an earlier dry run of the run.
     *
     * @param folder the run's report folder, from {@link ReportFiles#folder}
     * @param runId the run
     * @param figures the run's figures, each its metric, dimension and value
     * @param counts the run's issue counts, each its entity, stage, severity, code and count
     * @param issues the run's issues, a page at a time
     * @param pageSize how many issues a page holds
     */
    static void write(
            Path folder, long runId, List<Object[]> figures, List<Object[]> counts, IssuePages issues, int pageSize) {
        List<List<String>> figureRows = texts(figures);
        List<List<String>> shown = new ArrayList<>();
        long total = 0;
        try (ReportFiles.Lines csv = ReportFiles.lines(folder, "dry-run.csv")) {
            csv.write(CsvFormat.BOM
                    + CsvFormat.line(List.of(
                            "row",
                            "metric",
                            "dimension",
                            "value",
                            "entity",
                            "stage",
                            "severity",
                            "code",
                            "key_hash",
                            "detail")));
            for (List<String> figure : figureRows) {
                List<String> cells = new ArrayList<>(List.of("figure"));
                cells.addAll(figure);
                cells.addAll(List.of("", "", "", "", "", ""));
                csv.write(CsvFormat.line(cells));
            }
            long after = 0;
            List<Object[]> page;
            do {
                page = issues.after(after, pageSize);
                for (Object[] row : page) {
                    List<String> issue =
                            List.of(text(row[2]), text(row[1]), text(row[3]), text(row[4]), text(row[5]), text(row[6]));
                    List<String> cells = new ArrayList<>(List.of("issue", "", "", ""));
                    cells.addAll(issue);
                    csv.write(CsvFormat.line(cells));
                    if (shown.size() < ReportFiles.PAGE_ROWS) {
                        shown.add(issue);
                    }
                    total++;
                    after = ((Number) row[0]).longValue();
                }
            } while (page.size() == pageSize);
        }
        ReportFiles.file(folder, "dry-run.html", html(runId, figureRows, texts(counts), shown, total));
    }

    /**
     * The page: the figures, the issue counts, and the first issues.
     *
     * @param runId the run
     * @param figures metric, dimension, value
     * @param counts entity, stage, severity, code, count
     * @param shown the first issues, at most {@link ReportFiles#PAGE_ROWS}: entity, stage, severity, code, keyed hash,
     *     detail
     * @param total how many issues the run has
     * @return the page
     */
    static String html(
            long runId, List<List<String>> figures, List<List<String>> counts, List<List<String>> shown, long total) {
        return ReportFiles.page(
                "Dry run of import run " + runId,
                "The figures the sign-off reads and the run's issues, by code, keyed hash and number only; no legacy"
                        + " key or value.",
                List.of(
                        ReportFiles.table("Figures", List.of("Metric", "Dimension", "Value"), figures),
                        ReportFiles.table(
                                "Issues by entity, stage, severity and code",
                                List.of("Entity", "Stage", "Severity", "Code", "Issues"),
                                counts),
                        ReportFiles.table(
                                ReportFiles.caption("Issues", shown.size(), total, "dry-run.csv"),
                                List.of("Entity", "Stage", "Severity", "Code", "Keyed hash", "Detail"),
                                shown)));
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
