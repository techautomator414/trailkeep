// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static io.trailkeep.testsupport.ReportFixture.ALPHA;
import static io.trailkeep.testsupport.ReportFixture.MANAGER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.ReportFixture;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import java.time.InstantSource;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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
        assertThat(page(get("/reports"), status().isOk()))
                .contains("<li><a href=\"/reports\">Reports</a></li>")
                .contains("<a href=\"/reports/aging\">Aging</a>", "<a href=\"/reports/compliance\">SLA compliance</a>");

        assertThat(page(get("/reports/aging"), status().isOk()))
                .contains("<table class=\"table table-sm tk-report\"><caption>Open cases by age and SLA state, ")
                .contains("<thead><tr><th scope=\"col\">Team</th><th scope=\"col\">Case type</th>"
                        + "<th scope=\"col\">0–30 days</th><th scope=\"col\">31–60 days</th>")
                .contains(ALPHA_ROW + "<td>Access review</td>" + cells(3, 1, 2, 1, 2, 1, 3, 1) + "</tr>")
                .contains(ALPHA_ROW + "<td>Data handling</td>" + cells(0, 1, 0, 0, 0, 1, 0, 0) + "</tr>")
                .contains("<tfoot><tr><th scope=\"row\" colspan=\"2\">Total</th>" + cells(3, 2, 2, 1, 2, 2, 3, 1))
                .doesNotContain("Beta Reporting Team", "Synthetic report case", "Synthetic description");
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
                .contains("<p>67% of the cases closed in the period were closed within the SLA.</p>")
                .contains("<caption>Cases closed from " + from + " to " + to)
                .contains(ALPHA_ROW + "<td>Access review</td>" + cells(1, 1) + "<td>50%</td>" + cells(1, 3))
                .contains(ALPHA_ROW + "<td>Data handling</td>" + cells(1, 0) + "<td>100%</td>" + cells(1, 0))
                .contains("<th scope=\"row\" colspan=\"2\">Total</th>" + cells(2, 1) + "<td>67%</td>" + cells(2, 3)
                        + "</tr></tfoot>")
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
    @WithAppUser(id = ReportFixture.SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void someoneWithNeitherATeamRoleNorComplianceAdminIsForbidden() throws Exception {
        mvc.perform(get("/reports/aging")).andExpect(status().isForbidden());
        mvc.perform(get("/reports/compliance").param("from", "not a date")).andExpect(status().isForbidden());
    }

    @Test
    void anonymousRequestsGoToTheIdentityProvider() throws Exception {
        mvc.perform(get("/reports/aging")).andExpect(status().isFound());
        mvc.perform(get("/reports/compliance")).andExpect(status().isFound());
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
