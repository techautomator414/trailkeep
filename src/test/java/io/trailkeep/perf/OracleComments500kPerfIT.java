// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.perf;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The 500k seed on Oracle (WP-30), where {@code SEARCH_TEXT} is a {@code CLOB}, searched through the Oracle Text
 * adapter (ADR-0017). Opt-in, in the {@code perf-it} profile's {@code perf-500k-oracle} execution, which no workflow
 * runs (seeding takes minutes on Oracle; see {@code docs/perf/release-500k.md}). The image is {@code
 * trailkeep.oracle.image} as for {@code -Poracle-it}. The table goes to {@code target/perf/comments-500k-oracle.md}.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("perf-500k-oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleComments500kPerfIT extends AbstractComments500kPerfIT {

    static {
        // Representative redo (issue 308, deploy/README.md): the image's 20 MB groups stall one comment post in sixty
        // for about a minute; -Dtrailkeep.oracle.redo-mb=0 measures the image as shipped. Set before the container
        // starts, which is when the first Spring context loads.
        if (System.getProperty(OracleTestcontainersConfiguration.REDO_PROPERTY) == null) {
            System.setProperty(OracleTestcontainersConfiguration.REDO_PROPERTY, "512");
        }
    }

    /** A line holding only "/", which ends every statement of the Oracle seeds. */
    private static final Pattern SLASH_LINE = Pattern.compile("(?m)^/[ \\t]*$");

    private static final PerfReport REPORT = new PerfReport(
            "50 000 cases and 500 000 comments on Oracle (OracleComments500kPerfIT)", "comments-500k-oracle.md");

    private final OracleWaitDiagnostics diagnostics = new OracleWaitDiagnostics();

    @Autowired
    JdbcConnectionDetails connection;

    @Override
    void diagnosticsStart() {
        diagnostics.start(connection);
    }

    @Override
    void diagnosticsFinish() {
        diagnostics.finish().forEach(line -> report().add("Diagnostics, comment post: " + line));
    }

    @Override
    void applySeeds(DataSource dataSource) {
        // The scripts are written for SQL*Plus: every statement, PL/SQL blocks included, ends with a line holding only
        // "/", and no other line starts with one. Split on those lines, drop the comment lines, run each statement.
        for (String file : List.of("seed-cases-oracle.sql", "seed-comments-oracle.sql")) {
            String script;
            try {
                script = Files.readString(Path.of("perf", file));
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
            SLASH_LINE
                    .splitAsStream(script)
                    .map(statement -> statement
                            .lines()
                            .filter(line -> !line.startsWith("--"))
                            .collect(Collectors.joining("\n"))
                            .strip())
                    .filter(sql -> !sql.isEmpty())
                    .forEach(jdbc::execute);
        }
    }

    @Override
    List<String> plan(String sql) {
        // EXPLAIN PLAN needs no privilege on the V$ views (the executed cursor's plan does); this is the optimizer's
        // plan.
        jdbc.execute("EXPLAIN PLAN FOR " + sql);
        return jdbc.queryForList("SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY)", String.class);
    }

    @Override
    String searchCount(String word) {
        // OracleTextSearchGateway: CASE_SEARCH left-joined on the CONTEXT index; a word of letters is decided by the
        // index alone (ADR-0017).
        return "SELECT COUNT(*) FROM CASE_RECORD r LEFT JOIN CASE_SEARCH s ON s.CASE_ID = r.ID"
                + " AND CONTAINS(s.SEARCH_TEXT, '%" + word + "%') > 0 WHERE r.STATUS <> 'ARCHIVED'"
                + " AND (UPPER(r.CASE_NUMBER) LIKE '%"
                + word.toUpperCase(Locale.ROOT) + "%' OR s.CASE_ID IS NOT NULL)";
    }

    @Override
    PerfReport report() {
        return REPORT;
    }

    @AfterAll
    static void writeReport() throws IOException {
        REPORT.write();
    }
}
