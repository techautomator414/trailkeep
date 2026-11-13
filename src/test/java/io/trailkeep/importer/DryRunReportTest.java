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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code dry-run}'s preview report files (WP-26 part ab2, design §9): every figure and every issue as the database
 * holds them, by code, number and keyed hash, through the shared CSV writer (formula cells defused) and an escaped
 * page with no script.
 */
class DryRunReportTest {

    /** Two figures, one a planted formula, as the page shows them. */
    static final List<List<String>> FIGURES = List.of(List.of("CASES", "OPEN", "501"), List.of("=SUM(1)", "", "0"));

    /** One keyed issue and one run issue with a planted markup code, as the page shows them. */
    static final List<List<String>> ISSUES = List.of(
            List.of("CASE", "DRY_RUN", "ERROR", "TITLE_MISSING", "ab".repeat(32), ""),
            List.of("CASE", "DRY_RUN", "FATAL", "<b>&", "", "3"));

    @Test
    void bothFilesHoldTheFiguresAndTheIssuesInTheirColumns(@TempDir Path directory) throws Exception {
        Path folder = ReportFiles.folder(directory, 42);

        DryRunReport.write(
                folder,
                42,
                List.of(new Object[] {"CASES", "OPEN", 501L}, new Object[] {"SLA_FORECAST_DAY", null, 20_400L}),
                List.<Object[]>of(new Object[] {
                    Stage.DRY_RUN, Subject.CASE, Severity.ERROR, "TITLE_MISSING", "ab".repeat(32), null
                }));

        String csv = Files.readString(folder.resolve("dry-run.csv"));
        assertThat(csv)
                .startsWith(CsvFormat.BOM
                        + "\"row\",\"metric\",\"dimension\",\"value\",\"entity\",\"stage\",\"severity\",\"code\","
                        + "\"key_hash\",\"detail\"\r\n")
                .contains("\"figure\",\"CASES\",\"OPEN\",\"501\",\"\",\"\",\"\",\"\",\"\",\"\"\r\n")
                .contains("\"figure\",\"SLA_FORECAST_DAY\",\"\",\"20400\",")
                .contains("\"issue\",\"\",\"\",\"\",\"CASE\",\"DRY_RUN\",\"ERROR\",\"TITLE_MISSING\",\""
                        + "ab".repeat(32) + "\",\"\"\r\n");
        assertThat(Files.readString(folder.resolve("dry-run.html")))
                .contains("<th scope=\"row\">CASE</th><td>DRY_RUN</td><td>ERROR</td><td>TITLE_MISSING</td>");
    }

    @Test
    void formulaCellsAreDefusedAndThePageIsEscapedStaticAndLabelled() {
        assertThat(DryRunReport.csv(FIGURES, ISSUES)).contains("\"figure\",\"'=SUM(1)\",");

        String html = DryRunReport.html(42, FIGURES, ISSUES);

        assertThat(html)
                .startsWith("<!DOCTYPE html>\n<html lang=\"en\">")
                .contains("<title>Dry run of import run 42</title>", "default-src 'none'")
                .contains("<caption>Figures</caption>", "<caption>Issues</caption>")
                .contains("<th scope=\"col\">Keyed hash</th>", "<td>&lt;b&gt;&amp;</td>")
                .doesNotContain("<script", "<b>");
        assertThat(DryRunReport.html(43, List.of(), List.of())).contains("<td colspan=\"6\">None</td>");
    }
}
