// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.casefile.CaseFields;
import io.trailkeep.casefile.CasePriority;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Request Closure over HTTP (WP-19 part c1): an open case's page offers "Request closure" (a dialog opener that is
 * also a link to the same form as a page) to people who may request it, with the case type's outcomes and the
 * approvers the A.2 #18 rule allows; a refused form is 422 with every wrong field marked and summarized and the values
 * kept; CSRF is required; a Reader is 403 and an outsider 404; a request redirects to the case, which then shows the
 * pending-closure banner with the summary and follow-ups and no longer offers Request closure, Edit or Reassign.
 * Anonymous requests go to the identity provider.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ClosureControllerIT {

    static final long OWNER = 986_001L;
    static final long MANAGER = 986_002L;
    static final long READER = 986_003L;
    static final long OUTSIDER = 986_004L;
    static final long TEAM = 986_100L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CaseService cases;

    @Autowired
    private EntityManager entityManager;

    private CaseNumber number;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Closure Team')", TEAM);
        for (long id : new long[] {OWNER, MANAGER, READER, OUTSIDER}) {
            jdbc.update(
                    "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                            + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                    id,
                    "closure-subject-" + id,
                    "Synthetic Person " + id);
        }
        CaseSeed seed = new CaseSeed(jdbc);
        seed.member(OWNER, TEAM, TeamRole.ANALYST);
        seed.member(MANAGER, TEAM, TeamRole.MANAGER);
        seed.member(READER, TEAM, TeamRole.READER);
        jdbc.update(
                "INSERT INTO CASE_TYPE_OUTCOME (CASE_TYPE_ID, OUTCOME, SORT_ORDER) VALUES (?, 'Resolved', 1)",
                seed.caseType());
        number = cases.create(
                new Actor(new UserPermissions(OWNER, false, Set.of(), Map.of(TEAM, TeamRole.ANALYST)), "Owner"),
                new CaseFields(
                        seed.caseType(),
                        "Synthetic closure case",
                        CasePriority.NORMAL,
                        LocalDate.now(ZoneOffset.UTC).minusDays(1),
                        TEAM,
                        OWNER,
                        "Synthetic subject",
                        "Synthetic description",
                        Sensitivity.STANDARD));
        entityManager.flush();
    }

    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void openCaseOffersTheDialogWithTheOutcomesAndTheAllowedApprovers() throws Exception {
        String page = html(get("/cases/" + number));
        assertThat(page)
                .containsPattern("href=\"/cases/" + number + "/request-closure\"\\s+"
                        + "data-tk-dialog-open=\"tk-closure-dialog\"")
                .contains("<dialog id=\"tk-closure-dialog\" class=\"tk-dialog\"")
                .containsPattern("<option value=\"Resolved\">\\s*Resolved</option>")
                .contains("data-tk-count=\"{0} of {1} characters\"");
        // The approver select only (the page's Request update select lists the Reader, rightly).
        String approvers = page.substring(page.indexOf("id=\"tk-approver\""));
        assertThat(approvers.substring(0, approvers.indexOf("</select>")))
                .contains("<option value=\"" + MANAGER + "\">Synthetic Person " + MANAGER + "</option>")
                .doesNotContain("<option value=\"" + OWNER + "\">", "<option value=\"" + READER + "\">");
        assertThat(html(get("/cases/" + number + "/request-closure")))
                .contains("<h1>Request closure of " + number + "</h1>", "Back to the case")
                .doesNotContain("data-tk-dialog-focus");
    }

    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void refusedFieldsComeBackMarkedAndSummarizedWithTheirValues() throws Exception {
        String page = mvc.perform(send("Closed for good", " ", "f".repeat(5001), "not-an-id")
                        .with(csrf()))
                .andExpect(status().isUnprocessableContent())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(page)
                .contains("data-tk-error-summary", "Closure was not requested.")
                .contains("href=\"#tk-outcome\"", "href=\"#tk-summary\"")
                .contains("href=\"#tk-follow-ups\"", "href=\"#tk-approver\"")
                .contains("id=\"tk-outcome-error\"", "id=\"tk-summary-error\"", "id=\"tk-follow-ups-error\"")
                .contains("id=\"tk-approver-error\"", "f".repeat(5001));
        assertThat(page.split("aria-invalid=\"true\"", -1)).hasSize(5);
        mvc.perform(send("Resolved", "Summary", "", String.valueOf(MANAGER))).andExpect(status().isForbidden());
    }

    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void requestingShowsThePendingBannerAndHidesRequestEditAndReassign() throws Exception {
        String manager = String.valueOf(MANAGER);
        mvc.perform(send("Resolved", "A synthetic summary of the work", "A synthetic follow-up", manager)
                        .with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/cases/" + number));
        entityManager.clear();

        assertThat(html(get("/cases/" + number)))
                .contains("<h2 id=\"tk-pending-title\" class=\"h5\">Pending closure</h2>")
                .contains("Approver: Synthetic Person " + MANAGER + ". Outcome: Resolved.")
                .contains("A synthetic summary of the work", "A synthetic follow-up", "Pending closure</li>")
                .doesNotContain("tk-closure-dialog", "/edit\"", "/reassign\"");
        mvc.perform(get("/cases/" + number + "/request-closure")).andExpect(status().isForbidden());
    }

    @Test
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void readerIsNotOfferedTheRequestAndIsRefused() throws Exception {
        assertThat(html(get("/cases/" + number))).doesNotContain("tk-closure-dialog");
        mvc.perform(get("/cases/" + number + "/request-closure")).andExpect(status().isForbidden());
        mvc.perform(send("Resolved", "Summary", "", String.valueOf(MANAGER)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAppUser(id = OUTSIDER)
    void outsiderGetsNotFound() throws Exception {
        mvc.perform(get("/cases/" + number + "/request-closure")).andExpect(status().isNotFound());
        mvc.perform(send("Resolved", "Summary", "", String.valueOf(MANAGER)).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousRequestsGoToTheIdentityProvider() throws Exception {
        mvc.perform(get("/cases/" + number + "/request-closure")).andExpect(status().isFound());
        mvc.perform(send("Resolved", "Summary", "", String.valueOf(MANAGER)).with(csrf()))
                .andExpect(status().isFound());
    }

    private MockHttpServletRequestBuilder send(String outcome, String summary, String followUps, String approver) {
        return post("/cases/" + number + "/request-closure")
                .param("outcome", outcome)
                .param("summary", summary)
                .param("followUps", followUps)
                .param("approverId", approver);
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
