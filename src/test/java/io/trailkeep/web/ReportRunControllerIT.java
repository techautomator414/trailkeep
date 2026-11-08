// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static io.trailkeep.testsupport.ReportFixture.ALPHA;
import static io.trailkeep.testsupport.ReportFixture.BETA;
import static io.trailkeep.testsupport.ReportFixture.MANAGER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.ReportFixture;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.InstantSource;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.transaction.annotation.Transactional;

/**
 * The scheduled runs over HTTP (WP-23 part b1), on {@link ReportFixture} with runs written straight to the table, one
 * of them hand-edited: the aging page links to the runs for a Manager but not an Analyst; the list is a table with a
 * caption and headers, each row linking to the run and to its CSV (named for screen readers), paged by 20; a run's
 * page shows its stored report with the hand-edited script, handlers and foreign links stripped; the CSV is an
 * uncached, unsniffed attachment and audited. Everyone but the team's Managers and Compliance Admins gets 404, as do
 * malformed and unknown ids; anonymous requests go to the identity provider.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReportRunControllerIT {

    private static final long ADMIN = 984_006L;
    private static final long ALPHA_RUN = 984_601L;
    private static final long BETA_RUN = 984_602L;
    private static final long EDITED_RUN = 984_603L;
    private static final String REPORT = "<div class=\"table-responsive\"><table class=\"table table-sm tk-report\">"
            + "<caption>Overdue cases</caption><thead><tr><th scope=\"col\">Case</th></tr></thead><tbody><tr>"
            + "<th scope=\"row\"><a href=\"/cases/RPT-2026-984105\">RPT-2026-984105</a></th></tr></tbody>"
            + "</table></div>";
    private static final String EDITED = "<table><tbody><tr><td onerror=\"alert(1)\">cell<script>alert(2)</script></td>"
            + "<td><a href=\"javascript:alert(3)\">script link</a><a href=\"https://elsewhere.example.invalid/\">"
            + "external link</a><img src=\"x\" onerror=\"alert(4)\"></td></tr></tbody></table>";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private InstantSource clock;

    @Autowired
    private AppProperties app;

    private LocalDate today;

    @BeforeEach
    void seed() {
        today = LocalDate.ofInstant(clock.instant(), app.timezone());
        new ReportFixture(jdbc, today, app.timezone()).seed();
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, CREATED_AT) VALUES (?, ?, ?, ?, ?)",
                ADMIN,
                "report-admin",
                "Synthetic Report Admin",
                "report-admin@example.test",
                Timestamp.from(clock.instant()));
        jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, 'COMPLIANCE_ADMIN')", ADMIN);
        for (long team : List.of(ALPHA, BETA)) {
            jdbc.update(
                    "INSERT INTO REPORT_SCHEDULE (ID, TEAM_ID, RUN_WEEKDAY, RUN_HOUR, NEXT_RUN_AT)"
                            + " VALUES (?, ?, 1, 6, ?)",
                    team,
                    team,
                    Timestamp.from(clock.instant().plus(Duration.ofDays(3))));
        }
        run(ALPHA_RUN, ALPHA, 2, REPORT, "﻿\"Team\",\"Owner\"\r\n");
        run(BETA_RUN, BETA, 1, REPORT, "﻿\"Team\"\r\n");
        run(EDITED_RUN, ALPHA, 3, EDITED, "﻿\"Edited\"\r\n");
    }

    @Test
    @WithAppUser(id = MANAGER, team = ALPHA, teamRole = TeamRole.MANAGER)
    void managersFindTheirTeamsRunsFromTheAgingPageInATable() throws Exception {
        assertThat(page("/reports/aging", status().isOk()))
                .contains("<a class=\"ms-3\" href=\"/reports/runs\">Scheduled reports</a></p>");
        assertThat(page("/reports/runs", status().isOk()))
                .contains("<h1>Scheduled reports</h1>")
                .contains("<caption>Scheduled aging reports you receive, newest first</caption><thead><tr>"
                        + "<th scope=\"col\">Report</th><th scope=\"col\">Team</th><th scope=\"col\">Lines</th>"
                        + "<th scope=\"col\">CSV</th></tr></thead><tbody>")
                .contains(row(ALPHA_RUN, ReportFixture.ALPHA_NAME, today.minusDays(2)))
                .contains(row(EDITED_RUN, ReportFixture.ALPHA_NAME, today.minusDays(3)))
                .doesNotContain("/reports/runs/" + BETA_RUN, "Page 1 of", "Next page");
        assertThat(page("/reports/runs", status().isOk()).indexOf("/reports/runs/" + ALPHA_RUN))
                .isLessThan(page("/reports/runs", status().isOk()).indexOf("/reports/runs/" + EDITED_RUN));
    }

    @Test
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void complianceAdminsReadEveryTeamsRunsPagedByTwenty() throws Exception {
        for (int i = 1; i <= 20; i++) {
            run(984_700L + i, BETA, 10 + i, REPORT, "﻿\r\n");
        }
        assertThat(page("/reports/runs", status().isOk()))
                .contains("/reports/runs/" + ALPHA_RUN, "/reports/runs/" + BETA_RUN)
                .contains("<p>Page 1 of 2.</p>")
                .contains("<a class=\"page-link\" rel=\"next\" href=\"/reports/runs?page=2\">Next page</a>");
        assertThat(page("/reports/runs?page=2", status().isOk()))
                .contains("<a class=\"page-link\" rel=\"prev\" href=\"/reports/runs?page=1\">Previous page</a>")
                .contains("/reports/runs/984720\"");
        assertThat(page("/reports/runs?page=x", status().isOk())).contains("<p>Page 1 of 2.</p>");
    }

    @Test
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void eachRunsPageShowsItsStoredReportWithHandEditedMarkupStripped() throws Exception {
        assertThat(page("/reports/runs/" + ALPHA_RUN, status().isOk()))
                .contains("<title>Aging for Alpha Reporting Team as of " + today.minusDays(2) + " · ")
                .contains("<a class=\"ms-3\" href=\"/reports/runs/" + ALPHA_RUN + ".csv\">Export CSV</a>")
                .contains("<div>" + REPORT + "</div>");
        assertThat(page("/reports/runs/" + EDITED_RUN, status().isOk()))
                .contains("<div><table><tbody><tr><td>cell</td><td>script linkexternal link</td></tr></tbody>"
                        + "</table></div>")
                .doesNotContain("alert(", "javascript:", "elsewhere", "<img");
    }

    @Test
    @WithAppUser(id = MANAGER, team = ALPHA, teamRole = TeamRole.MANAGER)
    void theCsvIsTheStoredFileAsAnUncachedUnsniffedAuditedAttachment() throws Exception {
        MockHttpServletResponse csv = mvc.perform(get("/reports/runs/" + ALPHA_RUN + ".csv"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8"))
                .andExpect(header().string(
                                HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"aging-run-" + ALPHA_RUN + "-" + today.minusDays(2) + ".csv\""))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andReturn()
                .getResponse();
        assertThat(csv.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("﻿\"Team\",\"Owner\"\r\n");
        assertThat(audited())
                .as("one REPORT_RUN_DOWNLOADED by the Manager for the run")
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "SELECT ACTOR_ID FROM AUDIT_EVENT WHERE ACTION = 'REPORT_RUN_DOWNLOADED' AND ENTITY_ID = ?",
                        Long.class,
                        ALPHA_RUN))
                .isEqualTo(MANAGER);
    }

    @Test
    @WithAppUser(id = MANAGER, team = ALPHA, teamRole = TeamRole.MANAGER)
    void anotherTeamsRunAndMalformedOrUnknownIdsAreNotFound() throws Exception {
        for (String path : List.of(
                "/reports/runs/" + BETA_RUN,
                "/reports/runs/" + BETA_RUN + ".csv",
                "/reports/runs/abc",
                "/reports/runs/abc.csv",
                "/reports/runs/-1",
                "/reports/runs/984999999",
                "/reports/runs/984999999.csv",
                "/reports/runs/99999999999999999999")) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
        assertThat(audited()).isZero();
    }

    @Test
    @WithAppUser(id = ReportFixture.ANALYST, team = ALPHA, teamRole = TeamRole.ANALYST)
    void analystsOfTheTeamGetNotFoundAndNoLink() throws Exception {
        assertThat(page("/reports/aging", status().isOk())).doesNotContain("/reports/runs");
        mvc.perform(get("/reports/runs")).andExpect(status().isNotFound());
        mvc.perform(get("/reports/runs/" + ALPHA_RUN)).andExpect(status().isNotFound());
        mvc.perform(get("/reports/runs/" + ALPHA_RUN + ".csv")).andExpect(status().isNotFound());
        assertThat(audited()).isZero();
    }

    @Test
    @WithAppUser(id = MANAGER, team = ALPHA, teamRole = TeamRole.MANAGER)
    void disabledManagersGetNotFound() throws Exception {
        jdbc.update("UPDATE APP_USER SET DISABLED = TRUE WHERE ID = ?", MANAGER);
        mvc.perform(get("/reports/runs")).andExpect(status().isNotFound());
        mvc.perform(get("/reports/runs/" + ALPHA_RUN)).andExpect(status().isNotFound());
        mvc.perform(get("/reports/runs/" + ALPHA_RUN + ".csv")).andExpect(status().isNotFound());
    }

    @Test
    @WithAppUser(id = ReportFixture.SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void systemAdminsGetNotFound() throws Exception {
        mvc.perform(get("/reports/runs")).andExpect(status().isNotFound());
        mvc.perform(get("/reports/runs/" + ALPHA_RUN)).andExpect(status().isNotFound());
    }

    @Test
    void anonymousRequestsGoToTheIdentityProvider() throws Exception {
        mvc.perform(get("/reports/runs")).andExpect(status().isFound());
        mvc.perform(get("/reports/runs/" + ALPHA_RUN)).andExpect(status().isFound());
        mvc.perform(get("/reports/runs/" + ALPHA_RUN + ".csv")).andExpect(status().isFound());
    }

    private void run(long id, long team, int daysAgo, String html, String csv) {
        jdbc.update(
                "INSERT INTO REPORT_RUN (ID, SCHEDULE_ID, TEAM_ID, REPORT, RAN_AT, AS_OF, LINE_COUNT, HTML, CSV)"
                        + " VALUES (?, ?, ?, 'AGING', ?, ?, 3, ?, ?)",
                id,
                team,
                team,
                Timestamp.from(clock.instant().minus(Duration.ofDays(daysAgo))),
                Date.valueOf(today.minusDays(daysAgo)),
                html,
                csv);
    }

    private static String row(long id, String team, LocalDate asOf) {
        return "<tr><th scope=\"row\"><a href=\"/reports/runs/" + id + "\">Aging as of " + asOf + "</a></th><td>"
                + team + "</td><td>3</td><td><a href=\"/reports/runs/" + id + ".csv\"><span>Download CSV</span>"
                + "<span class=\"visually-hidden\">: " + team + ", aging as of " + asOf + "</span></a></td></tr>";
    }

    private long audited() {
        entityManager.flush();
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AUDIT_EVENT WHERE ACTION = 'REPORT_RUN_DOWNLOADED'", Long.class);
        return count == null ? 0 : count;
    }

    /** The page with the white space between tags removed, so rows read as one line. */
    private String page(String path, ResultMatcher expected) throws Exception {
        return mvc.perform(get(path))
                .andExpect(expected)
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll(">\\s+<", "><");
    }
}
