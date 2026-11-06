// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.casefile.CaseListQuery;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.SavedViewService;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The cases list at 50 000 cases on PostgreSQL (WP-15 part 5; IMPLEMENTATION_PLAN WP-15 "p95 < 500 ms at 50k"). The
 * synthetic seed {@code perf/seed-cases-postgresql.sql} is applied once and committed to this class's own container
 * (the {@code perf} failsafe execution runs in a JVM of its own, never next to other tests). Each scenario is a full
 * request through security, the controller, the scoped queries and Thymeleaf, sent {@value #WARM_UP} times to warm
 * up and then {@value #SAMPLES} times measured; the p50, p95 and max go to {@code target/perf/cases-list-50k.md}.
 * The targets are asserted: {@code GET /cases} p95 under 500 ms and the search p95 under 1 s.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("perf")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PerfCasesListIT {

    static final long ADMIN = 900_000_999L;
    static final long ANALYST = 900_000_001L;
    static final long ANALYST_TEAM = 900_000_001L;

    private static final Logger LOG = LoggerFactory.getLogger(PerfCasesListIT.class);
    private static final int WARM_UP = 10;
    private static final int SAMPLES = 60;
    private static final long LIST_TARGET_MS = 500;
    private static final long SEARCH_TARGET_MS = 1000;
    private static final AtomicBoolean SEEDED = new AtomicBoolean();
    private static final List<String> REPORT = Collections.synchronizedList(new ArrayList<>());

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SavedViewService views;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void seedOnce() {
        if (SEEDED.compareAndSet(false, true)) {
            final long started = System.nanoTime();
            new ResourceDatabasePopulator(new FileSystemResource(Path.of("perf", "seed-cases-postgresql.sql")))
                    .execute(dataSource);
            jdbc.update(
                    "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                            + " VALUES (?, 'perf-admin', 'Perf Compliance Admin', 'perf-admin@example.invalid', FALSE,"
                            + " CURRENT_TIMESTAMP)",
                    ADMIN);
            jdbc.update(
                    "INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, ?)",
                    ADMIN,
                    GlobalRole.COMPLIANCE_ADMIN.name());
            Actor admin = new Actor(
                    new UserPermissions(ADMIN, false, Set.of(GlobalRole.COMPLIANCE_ADMIN), Map.of()), "Perf Admin");
            for (int i = 1; i <= 20; i++) {
                views.save(admin, "Perf view " + i, CaseListQuery.parse(Map.of("q", List.of("view " + i))));
            }
            REPORT.add("Seed: " + jdbc.queryForObject("SELECT COUNT(*) FROM CASE_RECORD", Long.class)
                    + " cases, applied in " + (System.nanoTime() - started) / 1_000_000 + " ms");
        }
    }

    @Test
    @Order(1)
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void theDefaultListOfEveryCaseIsServedWithinTarget() throws Exception {
        assertThat(p95("GET /cases (Compliance Admin, every team, newest first)", get("/cases")))
                .isLessThan(LIST_TARGET_MS);
        assertThat(p95(
                        "GET /cases?sort=title (LOWER(TITLE) order)",
                        get("/cases").param("sort", "title")))
                .isLessThan(LIST_TARGET_MS);
        assertThat(p95(
                        "GET /cases?sort=due (SLA due date, left join)",
                        get("/cases").param("sort", "due")))
                .isLessThan(LIST_TARGET_MS);
        assertThat(p95(
                        "GET /cases?page=1900 (deep page, offset 47 475)",
                        get("/cases").param("page", "1900")))
                .isLessThan(LIST_TARGET_MS);
        assertThat(p95(
                        "GET /cases filtered (status, SLA state, priority, date range)",
                        get("/cases")
                                .param("status", "OPEN", "PENDING_CLOSURE")
                                .param("sla", "AT_RISK", "OVERDUE")
                                .param("priority", "HIGH")
                                .param("from", "2026-03-01")
                                .param("to", "2026-09-30")))
                .isLessThan(LIST_TARGET_MS);
    }

    @Test
    @Order(2)
    @WithAppUser(id = ANALYST, team = ANALYST_TEAM, teamRole = TeamRole.ANALYST)
    void teamAnalystsListsAreServedWithinTarget() throws Exception {
        assertThat(p95("GET /cases (Analyst, one team of 2 500, restricted only own)", get("/cases")))
                .isLessThan(LIST_TARGET_MS);
    }

    @Test
    @Order(3)
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void searchIsServedWithinTarget() throws Exception {
        assertThat(p95(
                        "GET /cases?q=plover audit (two words, every case)",
                        get("/cases").param("q", "plover audit")))
                .isLessThan(SEARCH_TARGET_MS);
        assertThat(p95(
                        "GET /cases?q=saltmarsh (one word, a fifth of the cases)",
                        get("/cases").param("q", "saltmarsh")))
                .isLessThan(SEARCH_TARGET_MS);
        assertThat(p95(
                        "GET /cases?q=PERF-2026-0499 (case number)",
                        get("/cases").param("q", "PERF-2026-0499")))
                .isLessThan(SEARCH_TARGET_MS);
    }

    @Test
    @Order(4)
    @WithAppUser(id = ANALYST, team = ANALYST_TEAM, teamRole = TeamRole.ANALYST)
    void teamAnalystsSearchesAreServedWithinTarget() throws Exception {
        assertThat(p95(
                        "GET /cases?q=plover audit (Analyst, one team)",
                        get("/cases").param("q", "plover audit")))
                .isLessThan(SEARCH_TARGET_MS);
    }

    @Test
    @Order(5)
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void anExportOfAboutTenThousandRowsIsTimed() throws Exception {
        long rows = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM CASE_RECORD WHERE CASE_TYPE_ID = 900000001 AND STATUS <> 'ARCHIVED'",
                Long.class));
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long started = System.nanoTime();
            String body = mvc.perform(
                            get("/cases/export.csv").param("type", "900000001").param("details", "true"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            times.add((System.nanoTime() - started) / 1_000_000);
            assertThat(body.split("\r\n", -1)).hasSize((int) rows + 2);
        }
        Collections.sort(times);
        REPORT.add(String.format(
                Locale.ROOT,
                "| GET /cases/export.csv?type=… (%d rows, details, %d runs) | %d | – | %d |",
                rows,
                times.size(),
                times.get(times.size() / 2),
                times.get(times.size() - 1)));
    }

    @Test
    @Order(6)
    void theSavedViewNavigationAndTheTeamOwnerChoicesAreTimed() {
        timed("Saved-view navigation query (20 views)", () -> views.views(ADMIN));
        timed(
                "DISTINCT TEAM_ID, OWNER_ID over every case (the filters' choices)",
                () -> jdbc.queryForList("SELECT DISTINCT TEAM_ID, OWNER_ID FROM CASE_RECORD"));
    }

    @Test
    @Order(7)
    void theHeaviestStatementsArePlannedAsExpected() {
        // Hand-written equivalents of what the list sends for a Compliance Admin (the scope is every team), timed by
        // the planner itself: the count, a page in title order, and the filters' DISTINCT choices.
        explain("count of every listed case", "SELECT COUNT(*) FROM CASE_RECORD WHERE STATUS <> 'ARCHIVED'");
        explain(
                "first page in LOWER(TITLE) order",
                "SELECT r.CASE_NUMBER, r.TITLE, t.NAME, s.SLA_STATE FROM CASE_RECORD r"
                        + " JOIN CASE_TYPE t ON t.ID = r.CASE_TYPE_ID LEFT JOIN CASE_SLA s ON s.CASE_ID = r.ID"
                        + " WHERE r.STATUS <> 'ARCHIVED' ORDER BY LOWER(r.TITLE), r.ID LIMIT 25");
        explain("DISTINCT team and owner pairs", "SELECT DISTINCT TEAM_ID, OWNER_ID FROM CASE_RECORD");
        explain(
                "two-word search subquery",
                "SELECT COUNT(*) FROM CASE_RECORD r WHERE r.STATUS <> 'ARCHIVED' AND r.ID IN (SELECT CASE_ID FROM"
                        + " CASE_SEARCH WHERE SEARCH_TEXT LIKE '%plover%' AND SEARCH_TEXT LIKE '%audit%')");
    }

    private void explain(String statement, String sql) {
        List<String> plan = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + sql, String.class);
        String time = plan.stream()
                .filter(line -> line.startsWith("Execution Time"))
                .findFirst()
                .orElse("Execution Time: unknown");
        String top = plan.get(0).strip();
        REPORT.add("Plan, " + statement + ": " + time + "; top node " + top.substring(0, Math.min(top.length(), 90)));
    }

    @AfterAll
    static void writeReport() throws IOException {
        Path out = Path.of("target", "perf", "cases-list-50k.md");
        Files.createDirectories(out.getParent());
        List<String> lines = new ArrayList<>();
        lines.add("# Cases list at 50 000 cases (PerfCasesListIT)");
        lines.add("");
        lines.add("| Scenario | p50 ms | p95 ms | max ms |");
        lines.add("|---|---:|---:|---:|");
        synchronized (REPORT) {
            REPORT.stream().filter(line -> line.startsWith("|")).forEach(lines::add);
            lines.add("");
            REPORT.stream().filter(line -> !line.startsWith("|")).forEach(lines::add);
        }
        Files.write(out, lines, StandardCharsets.UTF_8);
        lines.forEach(line -> LOG.info("{}", line));
    }

    /** Sends a request, warms up, measures, records the percentiles and returns the p95 in milliseconds. */
    private long p95(String scenario, MockHttpServletRequestBuilder request) throws Exception {
        for (int i = 0; i < WARM_UP; i++) {
            mvc.perform(request).andExpect(status().isOk());
        }
        List<Long> times = new ArrayList<>(SAMPLES);
        for (int i = 0; i < SAMPLES; i++) {
            long started = System.nanoTime();
            mvc.perform(request).andExpect(status().isOk());
            times.add((System.nanoTime() - started) / 1_000);
        }
        return record(scenario, times);
    }

    private void timed(String scenario, Runnable call) {
        for (int i = 0; i < WARM_UP; i++) {
            call.run();
        }
        List<Long> times = new ArrayList<>(SAMPLES);
        for (int i = 0; i < SAMPLES; i++) {
            long started = System.nanoTime();
            call.run();
            times.add((System.nanoTime() - started) / 1_000);
        }
        record(scenario, times);
    }

    /** Records p50, p95 and max of times in microseconds, as milliseconds; returns the p95 in whole milliseconds. */
    private static long record(String scenario, List<Long> times) {
        List<Long> sorted = new ArrayList<>(times);
        Collections.sort(sorted);
        long p50 = sorted.get(sorted.size() / 2);
        long p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
        REPORT.add(String.format(
                Locale.ROOT,
                "| %s | %.1f | %.1f | %.1f |",
                scenario,
                p50 / 1000.0,
                p95 / 1000.0,
                sorted.get(sorted.size() - 1) / 1000.0));
        return p95 / 1000;
    }
}
