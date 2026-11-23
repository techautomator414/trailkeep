// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The 500k seed (WP-30; BUILD_PLAN "Performance": 50k cases and 500k comments) in process, on each vendor: the 50 000
 * cases of {@code PerfCasesListIT} plus 500 000 comments with their search text and audit events (see {@code
 * perf/seed-comments-postgresql.sql}). Comments make each case's {@code CASE_SEARCH} text about seventeen times longer,
 * so what they can slow down is measured: the {@code LIKE} search (a scan of every case's whole text), the list, the
 * case page and History tab of a case with 208 comments, the aging report, and posting a comment to that case (the
 * append to a long text). Each scenario is a full request through security, the controller, the scoped queries and
 * Thymeleaf, timed by {@link PerfReport}. The targets are asserted: list and case pages p95 under 500 ms (the page
 * target of {@code docs/perf/cases-list-50k.md}), the search under 1 s, the aging report under 30 s.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class AbstractComments500kPerfIT {

    static final long ADMIN = 900_000_999L;
    static final long ANALYST = 900_000_001L;
    static final long ANALYST_TEAM = 900_000_001L;

    /** Case 100 of the seed: one of the 500 cases with 208 comments, in the Analyst's team. */
    static final String LONG_THREAD = "PERF-2026-000100";

    /** Case 101 of the seed: 8 comments, as most cases. */
    static final String SHORT_THREAD = "PERF-2026-000101";

    private static final long PAGE_TARGET_MS = 500;
    private static final long SEARCH_TARGET_MS = 1000;
    private static final long REPORT_TARGET_MS = 30_000;
    private static final AtomicBoolean SEEDED = new AtomicBoolean();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    /**
     * Applies the vendor's 50k case seed and then its 500k comment seed, as the schema owner.
     *
     * @param dataSource the application's data source
     */
    abstract void applySeeds(DataSource dataSource);

    /**
     * The vendor's plan of a statement as lines of text.
     *
     * @param sql the statement
     * @return the plan lines
     */
    abstract List<String> plan(String sql);

    /**
     * This run's report.
     *
     * @return the report
     */
    abstract PerfReport report();

    @BeforeEach
    void seedOnce() {
        if (SEEDED.compareAndSet(false, true)) {
            final long started = System.nanoTime();
            applySeeds(dataSource);
            jdbc.update(
                    "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                            + " VALUES (?, 'perf-admin', 'Perf Compliance Admin', 'perf-admin@example.invalid', ?,"
                            + " CURRENT_TIMESTAMP)",
                    ADMIN,
                    false);
            jdbc.update(
                    "INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, ?)",
                    ADMIN,
                    GlobalRole.COMPLIANCE_ADMIN.name());
            report().add("Seed: " + jdbc.queryForObject("SELECT COUNT(*) FROM CASE_RECORD", Long.class) + " cases, "
                    + jdbc.queryForObject("SELECT COUNT(*) FROM CASE_COMMENT", Long.class) + " comments, "
                    + jdbc.queryForObject("SELECT COUNT(*) FROM AUDIT_EVENT", Long.class) + " audit events, applied in "
                    + (System.nanoTime() - started) / 1_000_000 + " ms");
        }
    }

    @Test
    @Order(1)
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void theListAndTheSearchAreServedWithinTarget() throws Exception {
        // Every scenario is measured before any verdict, so a miss still leaves the other rows in the report.
        long list = report().p95(mvc, "GET /cases (Compliance Admin, every team, newest first)", get("/cases"));
        long titleWords = report().p95(
                        mvc,
                        "GET /cases?q=plover audit (two title words, every team)",
                        get("/cases").param("q", "plover audit"));
        long commentWord = report().p95(
                        mvc,
                        "GET /cases?q=kestrel (a word in 1 comment in 997: every text read whole)",
                        get("/cases").param("q", "kestrel"));
        long caseNumber = report().p95(
                        mvc,
                        "GET /cases?q=PERF-2026-0499 (case number)",
                        get("/cases").param("q", "PERF-2026-0499"));
        assertSoftly(soft -> {
            soft.assertThat(list).as("list p95").isLessThan(PAGE_TARGET_MS);
            soft.assertThat(titleWords).as("title-word search p95").isLessThan(SEARCH_TARGET_MS);
            soft.assertThat(commentWord).as("comment-word search p95").isLessThan(SEARCH_TARGET_MS);
            soft.assertThat(caseNumber).as("case-number search p95").isLessThan(SEARCH_TARGET_MS);
        });
    }

    @Test
    @Order(2)
    @WithAppUser(id = ANALYST, team = ANALYST_TEAM, teamRole = TeamRole.ANALYST)
    void anAnalystsSearchIsServedWithinTarget() throws Exception {
        assertThat(report().p95(
                                mvc,
                                "GET /cases?q=plover audit (Analyst, one team)",
                                get("/cases").param("q", "plover audit")))
                .isLessThan(SEARCH_TARGET_MS);
    }

    @Test
    @Order(3)
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void caseAndHistoryPagesAreServedWithinTarget() throws Exception {
        long shortThread = report().p95(mvc, "GET /cases/{number} (8 comments)", get("/cases/" + SHORT_THREAD));
        long longThread = report().p95(mvc, "GET /cases/{number} (208 comments)", get("/cases/" + LONG_THREAD));
        long history = report().p95(
                        mvc, "GET /cases/{number}/history (208 comments)", get("/cases/" + LONG_THREAD + "/history"));
        assertSoftly(soft -> {
            soft.assertThat(shortThread).as("case page p95, 8 comments").isLessThan(PAGE_TARGET_MS);
            soft.assertThat(longThread).as("case page p95, 208 comments").isLessThan(PAGE_TARGET_MS);
            soft.assertThat(history).as("History tab p95, 208 comments").isLessThan(PAGE_TARGET_MS);
        });
    }

    @Test
    @Order(4)
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void theAgingReportIsServedWithinTarget() throws Exception {
        assertThat(report().p95(mvc, "GET /reports/aging (every team)", get("/reports/aging")))
                .isLessThan(REPORT_TARGET_MS);
    }

    @Test
    @Order(5)
    @WithAppUser(id = ANALYST, team = ANALYST_TEAM, teamRole = TeamRole.ANALYST)
    void postingToLongThreadIsTimed() throws Exception {
        // Last, because it adds comments: the search text append, the audit event and the owner's mail on a case whose
        // text is already about 36 000 characters long.
        report().p95(
                        mvc,
                        "POST /cases/{number}/comments (208 comments and more)",
                        post("/cases/" + LONG_THREAD + "/comments")
                                .param("body", "Perf comment.")
                                .with(csrf()),
                        status().is3xxRedirection());
    }

    @Test
    @Order(6)
    void theSearchIsPlannedAsExpected() {
        plan("SELECT COUNT(*) FROM CASE_RECORD r WHERE r.STATUS <> 'ARCHIVED' AND r.ID IN (SELECT CASE_ID FROM"
                        + " CASE_SEARCH WHERE SEARCH_TEXT LIKE '%kestrel%')")
                .forEach(line -> report().add("Plan, search for a comment word: " + line));
    }
}
