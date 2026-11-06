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
 * Deciding a closure request over HTTP (WP-19 part c2). The named approver's case page offers Approve (one button)
 * and Reject or request changes (a dialog opener that is also a link to the same form as a page); approving closes
 * the case and shows its closure, locked; rejecting or requesting changes reopens it with the "Requires Changes"
 * badge and the reason until the next comment. A refused form is 422 with each wrong field marked and summarized, a
 * decision that lost a race 409 with Reload; CSRF is required; another Manager and the owner are 403, an outsider and a request id that is not the case's
 * pending request 404. Anonymous requests go to the identity provider.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ClosureDecisionControllerIT {

    static final long OWNER = 987_001L;
    static final long APPROVER = 987_002L;
    static final long COLLEAGUE = 987_003L;
    static final long OUTSIDER = 987_004L;
    static final long TEAM = 987_100L;

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
    private String decision = "";

    @BeforeEach
    void seedPendingRequest() {
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Decision Team')", TEAM);
        for (long id : new long[] {OWNER, APPROVER, COLLEAGUE, OUTSIDER}) {
            jdbc.update(
                    "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                            + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                    id,
                    "decision-subject-" + id,
                    "Synthetic Person " + id);
        }
        CaseSeed seed = new CaseSeed(jdbc);
        seed.member(OWNER, TEAM, TeamRole.ANALYST);
        seed.member(APPROVER, TEAM, TeamRole.MANAGER);
        seed.member(COLLEAGUE, TEAM, TeamRole.MANAGER);
        jdbc.update(
                "INSERT INTO CASE_TYPE_OUTCOME (CASE_TYPE_ID, OUTCOME, SORT_ORDER) VALUES (?, 'Resolved', 1)",
                seed.caseType());
        owner = new Actor(new UserPermissions(OWNER, false, Set.of(), Map.of(TEAM, TeamRole.ANALYST)), "Owner");
        number = cases.create(
                owner,
                new CaseFields(
                        seed.caseType(),
                        "Synthetic decision case",
                        CasePriority.NORMAL,
                        LocalDate.now(ZoneOffset.UTC).minusDays(1),
                        TEAM,
                        OWNER,
                        "Synthetic subject",
                        "Synthetic description",
                        Sensitivity.STANDARD));
        entityManager.flush();
        requestId = closures.requestClosure(
                owner, number.toString(), "Resolved", "A synthetic summary", "A synthetic follow-up", APPROVER);
        decision = "/cases/" + number + "/closure/" + requestId + "/decision";
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @WithAppUser(id = APPROVER, team = TEAM, teamRole = TeamRole.MANAGER)
    void approverIsOfferedApproveAndTheSendBackDialog() throws Exception {
        assertThat(html(get("/cases/" + number)))
                .containsPattern("action=\"" + decision + "\"[^>]*>\\s*<input type=\"hidden\" name=\"_csrf\"")
                .contains("<input type=\"hidden\" name=\"decision\" value=\"APPROVED\">")
                .contains("aria-describedby=\"tk-pending-text\">Approve closure</button>")
                .containsPattern("href=\"" + decision + "\"\\s+data-tk-dialog-open=\"tk-decision-dialog\"")
                .contains("<dialog id=\"tk-decision-dialog\" class=\"tk-dialog\"")
                .contains("id=\"tk-decision-REJECTED\"", "id=\"tk-decision-CHANGES_REQUESTED\"")
                .contains("maxlength=\"1000\"");
        assertThat(html(get(decision)))
                .contains("<h1>Reject or request changes on " + number + "</h1>", "A synthetic summary")
                .contains("A synthetic follow-up", "Back to the case")
                .doesNotContain("data-tk-dialog-focus");
    }

    @Test
    @WithAppUser(id = APPROVER, team = TEAM, teamRole = TeamRole.MANAGER)
    void approvingClosesTheCaseAndShowsItsClosureLocked() throws Exception {
        // The toast is a session notice, which the e2e walk covers (ClosureDecisionAccessibilityIT).
        mvc.perform(post(decision).param("decision", "APPROVED").with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/cases/" + number));
        entityManager.clear();
        assertThat(html(get("/cases/" + number)))
                .contains("Closed</li>")
                .contains("<h2 id=\"tk-decided-title\" class=\"h5\">Closure</h2>")
                .contains("Closed on approval by Synthetic Person " + APPROVER + " on ")
                .contains("Outcome: Resolved. The closure fields are locked.", "A synthetic summary")
                .doesNotContain("tk-decision-dialog", "<span>Requires Changes</span>", "tk-pending-title");
        mvc.perform(get(decision)).andExpect(status().isForbidden());
    }

    @Test
    @WithAppUser(id = APPROVER, team = TEAM, teamRole = TeamRole.MANAGER)
    void sendingBackShowsRequiresChangesUntilTheNextComment() throws Exception {
        mvc.perform(post(decision)
                        .param("decision", "CHANGES_REQUESTED")
                        .param("reason", "A synthetic reason")
                        .with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/cases/" + number));
        entityManager.clear();
        assertThat(html(get("/cases/" + number)))
                .contains("Open</li>")
                .contains("<span>Requires Changes</span></li>", "Changes were requested by Synthetic Person ")
                .contains("<h3 class=\"h6\">Reason</h3>", "A synthetic reason", "A synthetic summary")
                .doesNotContain("tk-pending-title");
        mvc.perform(post("/cases/" + number + "/comments")
                        .param("body", "A synthetic revision")
                        .with(csrf()))
                .andExpect(status().isFound());
        entityManager.clear();
        assertThat(html(get("/cases/" + number)))
                .doesNotContain("<span>Requires Changes</span>", "A synthetic reason", "tk-decided-title");
    }

    @Test
    @WithAppUser(id = APPROVER, team = TEAM, teamRole = TeamRole.MANAGER)
    void refusedFieldsComeBackMarkedAndSummarized() throws Exception {
        String page = refused(post(decision)
                .param("decision", "PENDING")
                .param("reason", "r".repeat(1001))
                .with(csrf()));
        assertThat(page)
                .contains("data-tk-error-summary", "The closure was not decided.")
                .contains("href=\"#tk-decision-REJECTED\"", "href=\"#tk-reason\"")
                .contains("id=\"tk-decision-error\"", "id=\"tk-reason-error\"", "r".repeat(1001));
        assertThat(page.split("aria-invalid=\"true\"", -1)).hasSize(4);
        assertThat(refused(post(decision)
                        .param("decision", "REJECTED")
                        .param("reason", " \r\n")
                        .with(csrf())))
                .contains("href=\"#tk-reason\"")
                .doesNotContain("href=\"#tk-decision-REJECTED\"");
        mvc.perform(post(decision).param("decision", "APPROVED")).andExpect(status().isForbidden());
        entityManager.clear();
        assertThat(html(get("/cases/" + number))).contains("tk-pending-title");
    }

    @Test
    @WithAppUser(id = APPROVER, team = TEAM, teamRole = TeamRole.MANAGER)
    void aDecisionThatLostTheRaceIsAConflictWithReload() throws Exception {
        // The test's one transaction shares the persistence context with the request: the request read here stays
        // managed at its version while the row moves on underneath, as a concurrent decision would move it.
        assertThat(closures.pending(owner, number.toString())).isPresent();
        jdbc.update("UPDATE CLOSURE_REQUEST SET VERSION = VERSION + 1 WHERE ID = ?", requestId);
        String page = mvc.perform(post(decision)
                        .param("decision", "REJECTED")
                        .param("reason", "A synthetic reason")
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(page)
                .contains("This request was decided or changed since the form was loaded.")
                .contains("href=\"/cases/" + number + "\">Reload</a>", "A synthetic reason");
    }

    @Test
    @WithAppUser(id = COLLEAGUE, team = TEAM, teamRole = TeamRole.MANAGER)
    void anotherManagerIsNotOfferedTheDecisionAndIsRefused() throws Exception {
        assertThat(html(get("/cases/" + number)))
                .contains("tk-pending-title")
                .doesNotContain("tk-decision-dialog", "Approve closure");
        mvc.perform(get(decision)).andExpect(status().isForbidden());
        mvc.perform(post(decision).param("decision", "APPROVED").with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void ownerNeverDecides() throws Exception {
        assertThat(html(get("/cases/" + number))).doesNotContain("tk-decision-dialog", "Approve closure");
        mvc.perform(post(decision).param("decision", "APPROVED").with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    @WithAppUser(id = APPROVER, team = TEAM, teamRole = TeamRole.MANAGER)
    void aRequestThatIsNotTheCasesPendingOneIsNotFound() throws Exception {
        mvc.perform(get("/cases/" + number + "/closure/not-an-id/decision")).andExpect(status().isNotFound());
        mvc.perform(post("/cases/" + number + "/closure/" + (requestId + 1) + "/decision")
                        .param("decision", "APPROVED")
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithAppUser(id = OUTSIDER)
    void outsiderGetsNotFound() throws Exception {
        mvc.perform(get(decision)).andExpect(status().isNotFound());
        mvc.perform(post(decision).param("decision", "APPROVED").with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void anonymousRequestsGoToTheIdentityProvider() throws Exception {
        mvc.perform(get(decision)).andExpect(status().isFound());
        mvc.perform(post(decision).param("decision", "APPROVED").with(csrf())).andExpect(status().isFound());
    }

    private String refused(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isUnprocessableContent())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
