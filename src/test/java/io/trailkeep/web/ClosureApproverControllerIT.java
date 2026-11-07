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
import io.trailkeep.closure.ClosureService;
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
 * Reassign approver and "Pending my approval" over HTTP (WP-19 part c2b). A Manager sees Reassign approver on a
 * pending case (a dialog opener that is also a link to the same form as a page) with the A.2 #18 choices for the
 * original requester minus the current approver; reassigning moves the banner's approver and alerts the new one. A
 * refused choice is 422 with the field marked and summarized, a reassignment that lost a race 409 with Reload; CSRF
 * is required; the owner Analyst is 403, an outsider and a request id that is not the case's pending request 404.
 * The approver's dashboard lists the request with Approve; nobody else's does.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ClosureApproverControllerIT {

    static final long OWNER = 988_001L;
    static final long APPROVER = 988_002L;
    static final long COLLEAGUE = 988_003L;
    static final long SUBSTITUTE = 988_004L;
    static final long OUTSIDER = 988_005L;
    static final long TEAM = 988_100L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CaseService cases;

    @Autowired
    private ClosureService closures;

    @Autowired
    private EntityManager entityManager;

    private Actor owner;
    private CaseNumber number;
    private long requestId;
    private String approver = "";

    @BeforeEach
    void seedPendingRequest() {
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Approver Team')", TEAM);
        for (long id : new long[] {OWNER, APPROVER, COLLEAGUE, SUBSTITUTE, OUTSIDER}) {
            jdbc.update(
                    "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                            + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                    id,
                    "approver-subject-" + id,
                    "Synthetic Person " + id);
        }
        CaseSeed seed = new CaseSeed(jdbc);
        seed.member(OWNER, TEAM, TeamRole.ANALYST);
        seed.member(APPROVER, TEAM, TeamRole.MANAGER);
        seed.member(COLLEAGUE, TEAM, TeamRole.MANAGER);
        seed.member(SUBSTITUTE, TEAM, TeamRole.MANAGER);
        jdbc.update(
                "INSERT INTO CASE_TYPE_OUTCOME (CASE_TYPE_ID, OUTCOME, SORT_ORDER) VALUES (?, 'Resolved', 1)",
                seed.caseType());
        owner = new Actor(new UserPermissions(OWNER, false, Set.of(), Map.of(TEAM, TeamRole.ANALYST)), "Owner");
        number = cases.create(
                owner,
                new CaseFields(
                        seed.caseType(),
                        "Synthetic approver case",
                        CasePriority.NORMAL,
                        LocalDate.now(ZoneOffset.UTC).minusDays(1),
                        TEAM,
                        OWNER,
                        "Synthetic subject",
                        "Synthetic description",
                        Sensitivity.STANDARD));
        entityManager.flush();
        requestId =
                closures.requestClosure(owner, number.toString(), "Resolved", "A synthetic summary", null, APPROVER);
        approver = "/cases/" + number + "/closure/" + requestId + "/approver";
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @WithAppUser(id = COLLEAGUE, team = TEAM, teamRole = TeamRole.MANAGER)
    void managerIsOfferedTheOtherApproversTheRuleAllows() throws Exception {
        String page = html(get("/cases/" + number));
        assertThat(page)
                .containsPattern("href=\"" + approver + "\"\\s+data-tk-dialog-open=\"tk-approver-dialog\"")
                .contains("<dialog id=\"tk-approver-dialog\" class=\"tk-dialog\"")
                .doesNotContain("tk-decision-dialog");
        String choices = page.substring(page.indexOf("id=\"tk-new-approver\""));
        assertThat(choices.substring(0, choices.indexOf("</select>")))
                .contains("<option value=\"" + COLLEAGUE + "\">", "<option value=\"" + SUBSTITUTE + "\">")
                .doesNotContain("<option value=\"" + APPROVER + "\">", "<option value=\"" + OWNER + "\">");
        assertThat(html(get(approver)))
                .contains("<h1>Reassign the approver of " + number + "</h1>", "Back to the case")
                .doesNotContain("data-tk-dialog-focus");
    }

    @Test
    @WithAppUser(id = COLLEAGUE, team = TEAM, teamRole = TeamRole.MANAGER)
    void reassigningMovesTheApproverAndAlertsTheNewOne() throws Exception {
        mvc.perform(post(approver)
                        .param("approverId", String.valueOf(SUBSTITUTE))
                        .with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/cases/" + number));
        // The test's one transaction shares the persistence context across requests; each real request has its own.
        entityManager.flush();
        entityManager.clear();
        assertThat(html(get("/cases/" + number)))
                .contains("Approver: Synthetic Person " + SUBSTITUTE + ". Outcome: Resolved.");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM USER_ALERT WHERE USER_ID = ?", Long.class, SUBSTITUTE))
                .isOne();
    }

    @Test
    @WithAppUser(id = COLLEAGUE, team = TEAM, teamRole = TeamRole.MANAGER)
    void refusedChoicesComeBackMarkedAndSummarized() throws Exception {
        for (String choice : new String[] {String.valueOf(APPROVER), "not-an-id", String.valueOf(OUTSIDER)}) {
            String page = mvc.perform(post(approver).param("approverId", choice).with(csrf()))
                    .andExpect(status().isUnprocessableContent())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(page)
                    .contains("data-tk-error-summary", "The approver was not changed.", "href=\"#tk-new-approver\"")
                    .contains("id=\"tk-new-approver-error\"");
            assertThat(page.split("aria-invalid=\"true\"", -1)).hasSize(2);
        }
        mvc.perform(post(approver).param("approverId", String.valueOf(SUBSTITUTE)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAppUser(id = COLLEAGUE, team = TEAM, teamRole = TeamRole.MANAGER)
    void reassignmentThatLostTheRaceIsAConflictWithReload() throws Exception {
        // As in ClosureDecisionControllerIT: the request stays managed at its version while the row moves on.
        assertThat(closures.pending(owner, number.toString())).isPresent();
        jdbc.update("UPDATE CLOSURE_REQUEST SET VERSION = VERSION + 1 WHERE ID = ?", requestId);
        assertThat(mvc.perform(post(approver)
                                .param("approverId", String.valueOf(SUBSTITUTE))
                                .with(csrf()))
                        .andExpect(status().isConflict())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("This request was decided or changed since the form was loaded.")
                .contains("href=\"/cases/" + number + "\">Reload</a>");
    }

    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void ownerAnalystIsNotOfferedTheReassignmentAndIsRefused() throws Exception {
        assertThat(html(get("/cases/" + number))).doesNotContain("tk-approver-dialog");
        mvc.perform(get(approver)).andExpect(status().isForbidden());
        mvc.perform(post(approver)
                        .param("approverId", String.valueOf(SUBSTITUTE))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAppUser(id = COLLEAGUE, team = TEAM, teamRole = TeamRole.MANAGER)
    void requestThatIsNotTheCasesPendingOneIsNotFound() throws Exception {
        mvc.perform(get("/cases/" + number + "/closure/not-an-id/approver")).andExpect(status().isNotFound());
        mvc.perform(post("/cases/" + number + "/closure/" + (requestId + 1) + "/approver")
                        .param("approverId", String.valueOf(SUBSTITUTE))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithAppUser(id = OUTSIDER)
    void outsiderGetsNotFound() throws Exception {
        mvc.perform(get(approver)).andExpect(status().isNotFound());
        mvc.perform(post(approver)
                        .param("approverId", String.valueOf(SUBSTITUTE))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousRequestsGoToTheIdentityProvider() throws Exception {
        mvc.perform(get(approver)).andExpect(status().isFound());
        mvc.perform(post(approver)
                        .param("approverId", String.valueOf(SUBSTITUTE))
                        .with(csrf()))
                .andExpect(status().isFound());
    }

    @Test
    @WithAppUser(id = APPROVER, team = TEAM, teamRole = TeamRole.MANAGER)
    void approversDashboardListsTheRequestWithApprove() throws Exception {
        String decision = "/cases/" + number + "/closure/" + requestId + "/decision";
        assertThat(html(get("/")))
                .contains("<h3 id=\"tk-approvals\" class=\"h5\">Pending my approval</h3>")
                .contains("Synthetic Person " + OWNER + " requested closure of " + number
                        + ", Synthetic approver case. Outcome: Resolved.")
                .containsPattern("action=\"" + decision + "\"")
                .contains("<input type=\"hidden\" name=\"decision\" value=\"APPROVED\">")
                .contains("aria-describedby=\"tk-approval-" + requestId + "\">Approve closure</button>")
                .contains("href=\"" + decision + "\"");
    }

    @Test
    @WithAppUser(id = COLLEAGUE, team = TEAM, teamRole = TeamRole.MANAGER)
    void nobodyElsesDashboardListsIt() throws Exception {
        assertThat(html(get("/"))).contains("Nothing needs your attention.").doesNotContain("Pending my approval");
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
