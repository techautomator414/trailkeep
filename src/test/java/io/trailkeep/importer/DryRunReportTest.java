// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.ImportIssue.Stage;
import io.trailkeep.importer.ImportIssue.Subject;
import io.trailkeep.shared.csv.CsvFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code dry-run}'s preview report files (WP-26 part ab2, design §9): every figure, the issue counts and the issues as
 * the database holds them, by code, number and keyed hash; the CSV lists every issue, read a page at a time, through
 * the shared CSV writer (formula cells defused); the escaped page with no script shows at most the first {@link
 * ReportFiles#PAGE_ROWS} issues and says so.
 */
class DryRunReportTest {

    /** Two figures, one a planted formula, as the page shows them. */
    static final List<List<String>> FIGURES = List.of(List.of("CASES", "OPEN", "501"), List.of("=SUM(1)", "", "0"));

    /** The issue counts of {@link #ISSUES}. */
    static final List<List<String>> COUNTS = List.of(
            List.of("CASE", "DRY_RUN", "ERROR", "TITLE_MISSING", "1"),
            List.of("CASE", "DRY_RUN", "FATAL", "<b>&", "1"));

    /** One keyed issue and one run issue with a planted markup code, as the page shows them. */
    static final List<List<String>> ISSUES = List.of(
            List.of("CASE", "DRY_RUN", "ERROR", "TITLE_MISSING", "ab".repeat(32), ""),
            List.of("CASE", "DRY_RUN", "FATAL", "<b>&", "", "3"));

    private static final Pattern ISSUE_ROW = Pattern.compile("^\"issue\",", Pattern.MULTILINE);

    @Test
    void theCsvListsEveryIssuePageByPageAndThePageOnlyTheFirstOnesSayingSo(@TempDir Path directory) throws Exception {
        Path folder = ReportFiles.folder(directory, 42);
        int total = ReportFiles.PAGE_ROWS + 201;
        List<Long> asked = new ArrayList<>();

        DryRunReport.write(
                folder,
                42,
                List.of(new Object[] {"CASES", "OPEN", 501L}, new Object[] {"SLA_FORECAST_DAY", null, 20_400L}),
                List.<Object[]>of(new Object[] {Subject.CASE, Stage.DRY_RUN, Severity.ERROR, "TITLE_MISSING", 1201L}),
                (afterId, limit) -> {
                    asked.add(afterId);
                    List<Object[]> page = new ArrayList<>();
                    for (long id = afterId + 1; id <= Math.min(total, afterId + limit); id++) {
                        page.add(new Object[] {
                            id, Stage.DRY_RUN, Subject.CASE, Severity.ERROR, "TITLE_MISSING", "ab".repeat(32), null
                        });
                    }
                    return page;
                },
                500);

        String csv = Files.readString(folder.resolve("dry-run.csv"));
        String html = Files.readString(folder.resolve("dry-run.html"));
        assertThat(asked).as("keyset pages of 500, the last one short").containsExactly(0L, 500L, 1000L);
        assertThat(csv)
                .startsWith(CsvFormat.BOM
                        + "\"row\",\"metric\",\"dimension\",\"value\",\"entity\",\"stage\",\"severity\",\"code\","
                        + "\"key_hash\",\"detail\"\r\n")
                .contains("\"figure\",\"CASES\",\"OPEN\",\"501\",\"\",\"\",\"\",\"\",\"\",\"\"\r\n")
                .contains("\"figure\",\"SLA_FORECAST_DAY\",\"\",\"20400\",")
                .contains("\"issue\",\"\",\"\",\"\",\"CASE\",\"DRY_RUN\",\"ERROR\",\"TITLE_MISSING\",\""
                        + "ab".repeat(32) + "\",\"\"\r\n");
        assertThat(ISSUE_ROW.matcher(csv).results().count()).as("every issue").isEqualTo(total);
        assertThat(html)
                .contains("<caption>Issues: the first 1000 of 1201; the full list is in dry-run.csv</caption>")
                .contains("<td>TITLE_MISSING</td><td>1201</td>");
        assertThat(html.split("<td>" + "ab".repeat(32) + "</td>", -1)).hasSize(ReportFiles.PAGE_ROWS + 1);
    }

    @Test
    void formulaCellsAreDefusedAndThePageIsEscapedStaticAndLabelled(@TempDir Path directory) throws Exception {
        Path folder = ReportFiles.folder(directory, 42);
        DryRunReport.write(
                folder,
                42,
                List.<Object[]>of(new Object[] {"=SUM(1)", null, 0L}),
                List.of(),
                (afterId, limit) -> List.of(),
                10);
        assertThat(Files.readString(folder.resolve("dry-run.csv"))).contains("\"figure\",\"'=SUM(1)\",");

        String html = DryRunReport.html(42, FIGURES, COUNTS, ISSUES, 2);

        assertThat(html)
                .startsWith("<!DOCTYPE html>\n<html lang=\"en\">")
                .contains("<title>Dry run of import run 42</title>", "default-src 'none'")
                .contains("<caption>Figures</caption>", "<caption>Issues</caption>")
                .contains("<caption>Issues by entity, stage, severity and code</caption>")
                .contains("<th scope=\"col\">Keyed hash</th>", "<td>&lt;b&gt;&amp;</td>")
                .doesNotContain("<script", "<b>");
        assertThat(DryRunReport.html(43, List.of(), List.of(), List.of(), 0)).contains("<td colspan=\"6\">None</td>");
    }

    @Test
    void reconcilesPageShowsAtMostTheFirstDifferencesSayingSo() {
        Reconciliation found = ReconcileReportTest.tally();
        for (int i = 0; i < ReportFiles.PAGE_ROWS; i++) {
            found.found(Subject.COMMENT, "WRITTEN_DIFFERS", "x" + i, (long) i);
        }

        String html = ReconcileReport.html(42, found, true, false);

        assertThat(html)
                .contains("<caption>Rows that differ: the first 1000 of 1001; the full list is in reconcile.csv"
                        + "</caption>");
        assertThat(Pattern.compile("<td>[0-9a-f]{64}</td>")
                        .matcher(html)
                        .results()
                        .count())
                .isEqualTo(ReportFiles.PAGE_ROWS);
        assertThat(ReconcileReport.csv(found, true, false).split("\"difference\",", -1))
                .hasSize(ReportFiles.PAGE_ROWS + 2);
    }
}
