// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static io.trailkeep.testsupport.ReportFixture.ALPHA;
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
import java.nio.charset.StandardCharsets;
import java.time.InstantSource;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The report pages over HTTP (WP-22 part a), on {@link ReportFixture}: the navigation links to Reports; the aging and
 * compliance pages are real tables with a caption, column and row headers and a total row in {@code <tfoot>}, cell
 * for cell what the fixture says for a Manager of its first team; the compliance page's period form is filled in and
 * a bad period is a 422 with the form marked and the raw value escaped; someone with neither a team role nor
 * Compliance Admin gets 403, and anonymous requests go to the identity provider.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReportControllerIT {

    private static final String ALPHA_ROW = "<tr><th scope=\"row\">Alpha Reporting Team</th>";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InstantSource clock;

    @Autowired
    private AppProperties app;

    private ReportFixture fixture;

    @BeforeEach
    void seed() {
        fixture = new ReportFixture(jdbc, LocalDate.ofInstant(clock.instant(), app.timezone()), app.timezone());
        fixture.seed();
    }

    @Test
    @WithAppUser(id = MANAGER, team = ALPHA, teamRole = TeamRole.MANAGER)
    void theAgingPageIsATableWithHeadersAndATotalRow() throws Exception {
        assertThat(page(get("/reports/aging"), status().isOk()))
                .contains("<li><a href=\"/reports/aging\">Reports</a></li>")
                .contains("<p class=\"tk-report-links\"><a href=\"/reports/compliance\">SLA compliance</a>")
                .contains("<table class=\"table table-sm tk-report\"><caption>Open and pending-closure cases on "
                        + LocalDate.ofInstant(clock.instant(), app.timezone()) + ", by days since identified")
                .contains("<thead><tr><th scope=\"col\">Team</th><th scope=\"col\">Owner</th>"
                        + "<th scope=\"col\">0–30 days</th><th scope=\"col\">31–60 days</th>")
                .contains(ALPHA_ROW + "<td>Synthetic Report Analyst</td>" + cells(2, 2, 0, 0, 1, 2, 1, 0) + "</tr>")
                .contains(ALPHA_ROW + "<td>Synthetic Report Manager</td>" + cells(0, 0, 2, 1, 1, 0, 1, 1) + "</tr>")
                .contains(ALPHA_ROW + "<td>Synthetic Restricted Owner</td>" + cells(0, 1, 0, 0, 0, 0, 1, 0) + "</tr>")
                .contains("<tfoot><tr><th scope=\"row\" colspan=\"2\">Total</th>" + cells(2, 3, 2, 1, 2, 2, 3, 1))
                .doesNotContain("Beta Reporting Team", "Synthetic description", ReportFixture.MESSAGE);
    }

    @Test
    @WithAppUser(id = MANAGER, team = ALPHA, teamRole = TeamRole.MANAGER)
    void theAgingPageListsOverdueCasesAndUnansweredRequestsInTables() throws Exception {
        LocalDate today = LocalDate.ofInstant(clock.instant(), app.timezone());

        assertThat(page(get("/reports/aging"), status().isOk()))
                .contains("<table class=\"table table-sm tk-report tk-report-list\">"
                        + "<caption>Overdue cases, oldest due first</caption><thead><tr><th scope=\"col\">Case</th>"
                        + "<th scope=\"col\">Title</th><th scope=\"col\">Owner</th><th scope=\"col\">Due</th>"
                        + "<th scope=\"col\">Days overdue</th></tr></thead><tbody>" + listed(105)
                        + "<td>Synthetic Report Manager</td><td>" + today.minusDays(61) + "</td><td>61</td></tr>")
                .contains(listed(106) + "<td>Synthetic Restricted Owner</td><td>" + today.minusDays(5) + "</td>")
                .contains("<caption>Unanswered update requests, oldest first</caption><thead><tr>"
                        + "<th scope=\"col\">Case</th><th scope=\"col\">Title</th><th scope=\"col\">Asked of</th>"
                        + "<th scope=\"col\">Asked by</th><th scope=\"col\">Requested on</th>"
                        + "<th scope=\"col\">Days waiting</th></tr></thead><tbody>" + listed(106)
                        + "<td>Synthetic Restricted Owner</td><td>Synthetic Report Manager</td><td>"
                        + today.minusDays(8) + "</td><td>8</td></tr>" + listed(100))
                .doesNotContain("Only the first", "None.", ReportFixture.MESSAGE);
    }

    @Test
    @WithAppUser(id = ReportFixture.BETA_ANALYST, team = 984_013L, teamRole = TeamRole.MANAGER)
    void emptyListsSayNone() throws Exception {
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, ?)", 984_013L, "Empty Reporting Team");
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE USER_ID = ?", ReportFixture.BETA_ANALYST);
        jdbc.update(
                "INSERT INTO APP_USER_TEAM (USER_ID, TEAM_ID, ROLE_IN_TEAM) VALUES (?, 984013, 'MANAGER')",
                ReportFixture.BETA_ANALYST);

        assertThat(page(get("/reports/aging"), status().isOk()))
                .contains("<tbody><tr><td colspan=\"5\">None.</td></tr></tbody>")
                .contains("<tbody><tr><td colspan=\"6\">None.</td></tr></tbody>");
    }

    /** A listed row's first two cells for the fixture's case with this id suffix: its linked number and title. */
    private static String listed(int suffix) {
        String number = "RPT-2026-984" + suffix;
        return "<tr><th scope=\"row\"><a href=\"/cases/" + number + "\">" + number
                + "</a></th><td>Synthetic report case 984" + suffix + "</td>";
    }

    @Test
    @WithAppUser(id = MANAGER, team = ALPHA, teamRole = TeamRole.MANAGER)
    void theCompliancePageShowsThePeriodAndTheShareWithinTheSla() throws Exception {
        String from = fixture.from().toString();
        String to = fixture.to().toString();

        assertThat(page(get("/reports/compliance").param("from", from).param("to", to), status().isOk()))
                .contains("<input class=\"form-control\" type=\"date\" id=\"tk-from\" name=\"from\" value=\"" + from
                        + "\">")
                .contains("<label class=\"form-label\" for=\"tk-to\">To</label>")
                .contains("<caption>Cases closed from " + from + " to " + to)
                .contains("<th scope=\"col\">Closed late</th><th scope=\"col\">At risk</th>"
                        + "<th scope=\"col\">Overdue</th><th scope=\"col\">Share within SLA</th>")
                .contains(ALPHA_ROW + "<td>Access review</td>" + cells(1, 1, 1, 3) + "<td>50%</td></tr>")
                .contains(ALPHA_ROW + "<td>Data handling</td>" + cells(1, 0, 1, 0) + "<td>100%</td></tr>")
                .contains(
                        "<th scope=\"row\" colspan=\"2\">Total</th>" + cells(2, 1, 2, 3) + "<td>67%</td></tr></tfoot>")
                .doesNotContain("tk-period-error");
        assertThat(page(get("/reports/compliance"), status().isOk()))
                .contains("name=\"to\" value=\"" + LocalDate.ofInstant(clock.instant(), app.timezone()) + "\"");
    }

    @Test
    @WithAppUser(id = MANAGER, team = ALPHA, teamRole = TeamRole.MANAGER)
    void badPeriodsAreUnprocessableWithTheFormMarked() throws Exception {
        assertThat(page(
                        get("/reports/compliance")
                                .param("from", "<b>2026-13-01")
                                .param("to", ""),
                        unprocessable()))
                .contains("<div class=\"alert alert-danger\" role=\"alert\" id=\"tk-period-error\">Choose a period")
                .contains("name=\"from\" value=\"&lt;b&gt;2026-13-01\" aria-invalid=\"true\""
                        + " aria-describedby=\"tk-period-error\">")
                .doesNotContain("<b>2026", "<table");
        assertThat(page(period("2026-02-01", "2026-01-31"), unprocessable()))
                .contains("value=\"2026-02-01\"", "value=\"2026-01-31\"");
        assertThat(page(period("2024-01-01", "2025-01-01"), unprocessable())).contains("id=\"tk-period-error\"");
        assertThat(page(period("2024-01-01", "2024-12-31"), status().isOk())).doesNotContain("id=\"tk-period-error\"");
    }

    @Test
    @WithAppUser(id = MANAGER, team = ALPHA, teamRole = TeamRole.MANAGER)
    void theCsvIsAnUncachedUnsniffedAttachmentLinkedFromEachReport() throws Exception {
        String from = fixture.from().toString();
        String to = fixture.to().toString();
        assertThat(page(get("/reports/aging"), status().isOk()))
                .contains("<a class=\"ms-3\" href=\"/reports/aging.csv\">" + "Export CSV</a>");
        assertThat(page(period(from, to), status().isOk()))
                .contains("href=\"/reports/compliance.csv?from=" + from + "&amp;to=" + to + "\">Export CSV</a>");
        assertThat(page(period(to, from), unprocessable())).doesNotContain("compliance.csv");

        MockHttpServletResponse aging = csv(get("/reports/aging.csv"));
        assertThat(aging.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"aging-" + LocalDate.ofInstant(clock.instant(), app.timezone())
                        + ".csv\"");
        assertThat(aging.getContentAsString(StandardCharsets.UTF_8))
                .startsWith("﻿\"Team\",\"Owner\",\"0-30 days\"")
                .contains("\"Alpha Reporting Team\",\"Synthetic Report Manager\",\"0\",\"0\",\"2\",\"1\",")
                .contains("\"Total\",\"\",\"2\",\"3\",\"2\",\"1\",\"2\",\"2\",\"3\",\"1\"\r\n\r\n\"Overdue case\",")
                .contains("\r\n\"Unanswered request on\",\"Title\",\"Asked of\",");
        MockHttpServletResponse compliance =
                csv(get("/reports/compliance.csv").param("from", from).param("to", to));
        assertThat(compliance.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"sla-compliance-" + from + "-to-" + to + ".csv\"");
        assertThat(compliance.getContentAsString(StandardCharsets.UTF_8))
                .endsWith("\"Total\",\"\",\"2\",\"1\",\"2\",\"3\",\"67\"\r\n");
        mvc.perform(get("/reports/compliance.csv").param("from", to).param("to", from))
                .andExpect(unprocessable());
    }

    @Test
    @WithAppUser(id = ReportFixture.SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void someoneWithNeitherATeamRoleNorComplianceAdminIsForbidden() throws Exception {
        mvc.perform(get("/reports/aging")).andExpect(status().isForbidden());
        mvc.perform(get("/reports/compliance").param("from", "not a date")).andExpect(status().isForbidden());
        mvc.perform(get("/reports/aging.csv")).andExpect(status().isForbidden());
        mvc.perform(get("/reports/compliance.csv")).andExpect(status().isForbidden());
    }

    @Test
    void anonymousRequestsGoToTheIdentityProvider() throws Exception {
        mvc.perform(get("/reports/aging")).andExpect(status().isFound());
        mvc.perform(get("/reports/compliance")).andExpect(status().isFound());
        mvc.perform(get("/reports/aging.csv")).andExpect(status().isFound());
        mvc.perform(get("/reports/compliance.csv")).andExpect(status().isFound());
    }

    private MockHttpServletResponse csv(MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletResponse response = mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andReturn()
                .getResponse();
        return response;
    }

    /** The page with the white space between tags removed, so rows read as one line. */
    private String page(MockHttpServletRequestBuilder request, ResultMatcher expected) throws Exception {
        return mvc.perform(request)
                .andExpect(expected)
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll(">\\s+<", "><");
    }

    private static MockHttpServletRequestBuilder period(String from, String to) {
        return get("/reports/compliance").param("from", from).param("to", to);
    }

    private static ResultMatcher unprocessable() {
        return status().isUnprocessableContent();
    }

    private static String cells(long... counts) {
        StringBuilder cells = new StringBuilder();
        for (long count : counts) {
            cells.append("<td>").append(count).append("</td>");
        }
        return cells.toString();
    }
}
