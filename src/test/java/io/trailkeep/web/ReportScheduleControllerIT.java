// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Map;
import java.util.Objects;
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
 * The report schedules over HTTP (WP-23 part b2): a Compliance Admin reaches them from the other admin screens and the
 * runs list, sees every team with its status, opens a team's form (labelled controls, the version hidden), and saves
 * it (CSRF required) back to the list, the schedule created or changed and audited. A value no form offers is a 422
 * with the form marked; a stale version a 409 with the input kept, the conflict panel and Reload but no Save. A
 * Manager gets 403 on every request, an unknown or malformed team 404; anonymous requests go to the identity
 * provider.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReportScheduleControllerIT {

    private static final long ADMIN = 984_901L;
    private static final long MANAGER = 984_902L;
    private static final long NEW_TEAM = 984_911L;
    private static final long OLD_TEAM = 984_912L;
    private static final String FORM = "/admin/report-schedules/" + NEW_TEAM;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private InstantSource clock;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, ?)", NEW_TEAM, "Aardvark Schedule Team");
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, ?)", OLD_TEAM, "Aardwolf Schedule Team");
        for (long user : new long[] {ADMIN, MANAGER}) {
            jdbc.update(
                    "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, CREATED_AT) VALUES (?, ?, ?, ?, ?)",
                    user,
                    "schedule-" + user,
                    "Synthetic Schedule User",
                    "schedule-" + user + "@example.test",
                    now);
        }
        jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, 'COMPLIANCE_ADMIN')", ADMIN);
        jdbc.update(
                "INSERT INTO APP_USER_TEAM (USER_ID, TEAM_ID, ROLE_IN_TEAM) VALUES (?, ?, 'MANAGER')",
                MANAGER,
                NEW_TEAM);
        jdbc.update(
                "INSERT INTO REPORT_SCHEDULE (ID, TEAM_ID, ENABLED, RUN_WEEKDAY, RUN_HOUR, NEXT_RUN_AT, VERSION)"
                        + " VALUES (?, ?, FALSE, 5, 14, ?, 3)",
                OLD_TEAM,
                OLD_TEAM,
                Timestamp.from(clock.instant().plus(Duration.ofDays(2))));
    }

    @Test
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void complianceAdminsReachEveryTeamsScheduleFromTheAdminScreens() throws Exception {
        for (String screen : new String[] {"/admin/sla-policies", "/admin/case-types", "/reports/runs"}) {
            assertThat(page(get(screen), status().isOk()))
                    .contains("href=\"/admin/report-schedules\">Report schedules");
        }
        assertThat(page(get("/admin/report-schedules"), status().isOk()))
                .contains("<h1>Report schedules</h1>")
                .contains("<caption>The weekly aging report of every team</caption>")
                .contains("<tr><th scope=\"row\"><a href=\"" + FORM + "\">Aardvark Schedule Team</a></th>"
                        + "<td>Not scheduled</td><td>—</td><td>—</td></tr>")
                .contains("<tr><th scope=\"row\"><a href=\"/admin/report-schedules/" + OLD_TEAM
                        + "\">Aardwolf Schedule Team</a></th><td>Disabled</td><td>Fridays at 14:00</td>"
                        + "<td>—</td></tr>");
        assertThat(page(get(FORM), status().isOk()))
                .contains("<h1>Aging report for Aardvark Schedule Team</h1>")
                .contains("<input type=\"hidden\" name=\"version\" value=\"-1\">")
                .contains("<input class=\"form-check-input\" type=\"checkbox\" id=\"enabled\" name=\"enabled\""
                        + " value=\"true\" checked=\"checked\">")
                .contains("<label class=\"form-label\" for=\"weekday\">Day</label>")
                .contains("<option value=\"MONDAY\" selected=\"selected\">Monday</option>")
                .contains("<option value=\"6\" selected=\"selected\">06:00</option>")
                .doesNotContain("role=\"alert\"");
    }

    @Test
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void savingCreatesOrChangesTheScheduleAndReturnsToTheList() throws Exception {
        mvc.perform(save(FORM, "true", "THURSDAY", "8", "-1").with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/report-schedules"));
        mvc.perform(save("/admin/report-schedules/" + OLD_TEAM, "true", "SUNDAY", "23", "3")
                        .with(csrf()))
                .andExpect(redirectedUrl("/admin/report-schedules"));
        entityManager.flush();

        Map<String, Object> created = schedule(NEW_TEAM);
        assertThat(number(created, "RUN_WEEKDAY")).isEqualTo(4);
        assertThat(number(created, "RUN_HOUR")).isEqualTo(8);
        assertThat(created.get("ENABLED")).isEqualTo(true);
        Map<String, Object> changed = schedule(OLD_TEAM);
        assertThat(number(changed, "RUN_WEEKDAY")).isEqualTo(7);
        assertThat(number(changed, "VERSION")).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM AUDIT_EVENT WHERE ACTION = 'REPORT_SCHEDULE_CHANGED' AND ACTOR_ID = ?",
                        Long.class,
                        ADMIN))
                .isEqualTo(2L);
        assertThat(page(get("/admin/report-schedules"), status().isOk()))
                .contains("Aardvark Schedule Team</a></th><td>Enabled</td><td>Thursdays at 08:00</td><td>20");
    }

    @Test
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void valuesNoFormOffersAreUnprocessableWithTheFormMarked() throws Exception {
        for (MockHttpServletRequestBuilder bad : new MockHttpServletRequestBuilder[] {
            save(FORM, "true", "FUNDAY", "8", "-1"),
            save(FORM, "true", "MONDAY", "24", "-1"),
            save(FORM, "true", "MONDAY", "x", "-1"),
            post(FORM).param("version", "-1")
        }) {
            assertThat(page(bad.with(csrf()), status().isUnprocessableContent()))
                    .contains("<div class=\"alert alert-danger\" role=\"alert\" tabindex=\"-1\" data-tk-error-summary>")
                    .contains("<button class=\"btn btn-primary\" type=\"submit\">Save schedule</button>");
        }
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM REPORT_SCHEDULE WHERE TEAM_ID = ?", Long.class, NEW_TEAM))
                .isZero();
    }

    @Test
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void staleVersionsAreConflictsWithTheInputKeptAndReloadInsteadOfSave() throws Exception {
        String conflict = page(
                save("/admin/report-schedules/" + OLD_TEAM, "true", "TUESDAY", "9", "2")
                        .with(csrf()),
                status().isConflict());
        assertThat(conflict)
                .contains("<h2 id=\"tk-conflict-title\" class=\"h5\">This schedule changed since you opened it</h2>")
                .contains("<a class=\"btn btn-primary\" href=\"/admin/report-schedules/" + OLD_TEAM + "\">Reload</a>")
                .contains("<option value=\"TUESDAY\" selected=\"selected\">Tuesday</option>")
                .contains("<option value=\"9\" selected=\"selected\">09:00</option>")
                .contains("<input type=\"hidden\" name=\"version\" value=\"2\">")
                .doesNotContain("Save schedule");
        page(save(FORM, "true", "TUESDAY", "9", "").with(csrf()), status().isConflict());
        entityManager.flush();
        assertThat(number(schedule(OLD_TEAM), "RUN_WEEKDAY")).isEqualTo(5);
    }

    @Test
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void unknownAndMalformedTeamsAreNotFoundAndSavingNeedsCsrf() throws Exception {
        mvc.perform(get("/admin/report-schedules/984999")).andExpect(status().isNotFound());
        mvc.perform(get("/admin/report-schedules/abc")).andExpect(status().isNotFound());
        mvc.perform(save("/admin/report-schedules/abc", "true", "MONDAY", "6", "-1")
                        .with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(save(FORM, "true", "MONDAY", "6", "-1")).andExpect(status().isForbidden());
    }

    @Test
    @WithAppUser(id = MANAGER, team = NEW_TEAM, teamRole = TeamRole.MANAGER)
    void managersAreForbiddenEverywhere() throws Exception {
        mvc.perform(get("/admin/report-schedules")).andExpect(status().isForbidden());
        mvc.perform(get(FORM)).andExpect(status().isForbidden());
        mvc.perform(save(FORM, "true", "FUNDAY", "6", "-1").with(csrf())).andExpect(status().isForbidden());
        assertThat(page(get("/reports/runs"), status().isOk())).doesNotContain("/admin/report-schedules");
    }

    @Test
    void anonymousRequestsGoToTheIdentityProvider() throws Exception {
        mvc.perform(get("/admin/report-schedules")).andExpect(status().isFound());
        mvc.perform(get(FORM)).andExpect(status().isFound());
    }

    private Map<String, Object> schedule(long team) {
        return jdbc.queryForMap(
                "SELECT ENABLED, RUN_WEEKDAY, RUN_HOUR, VERSION FROM REPORT_SCHEDULE WHERE TEAM_ID = ?", team);
    }

    private static int number(Map<String, Object> row, String column) {
        return ((Number) Objects.requireNonNull(row.get(column), column)).intValue();
    }

    private static MockHttpServletRequestBuilder save(
            String path, String enabled, String weekday, String hour, String version) {
        return post(path)
                .param("enabled", enabled)
                .param("weekday", weekday)
                .param("hour", hour)
                .param("version", version);
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
}
