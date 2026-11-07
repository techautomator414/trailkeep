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
import io.trailkeep.closure.ClosureDecision;
import io.trailkeep.closure.ClosureDecisionService;
import io.trailkeep.closure.ClosureService;
import io.trailkeep.security.CaseAction;
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
 * Reopen over HTTP (WP-19 part c3). A closed case's page offers Reopen to a Manager (a dialog opener that is also a
 * link to the same form as a page); reopening with a reason brings the case back to Open with the "Reopened" chip,
 * the new SLA cycle's chip and Request closure offered again. A missing or over-long reason is 422 with the field
 * marked and summarized, a case that moved on 409 with Reload; CSRF is required; the owner Analyst is 403, so is
 * reopening an open case, and an outsider 404. Anonymous requests go to the identity provider.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CaseReopenControllerIT {

    static final long OWNER = 989_001L;
    static final long APPROVER = 989_002L;
    static final long MANAGER = 989_003L;
    static final long OUTSIDER = 989_004L;
    static final long TEAM = 989_100L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CaseService cases;

    @Autowired
    private ClosureService closures;

    @Autowired
    private ClosureDecisionService decisions;

    @Autowired
    private EntityManager entityManager;

    private CaseNumber number;
    private String reopen = "";

    @BeforeEach
    void seedClosedCase() {
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Reopen Team')", TEAM);
        for (long id : new long[] {OWNER, APPROVER, MANAGER, OUTSIDER}) {
            jdbc.update(
                    "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                            + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                    id,
                    "reopen-subject-" + id,
                    "Synthetic Person " + id);
        }
        CaseSeed seed = new CaseSeed(jdbc);
        seed.member(OWNER, TEAM, TeamRole.ANALYST);
        seed.member(APPROVER, TEAM, TeamRole.MANAGER);
        seed.member(MANAGER, TEAM, TeamRole.MANAGER);
        jdbc.update(
                "INSERT INTO CASE_TYPE_OUTCOME (CASE_TYPE_ID, OUTCOME, SORT_ORDER) VALUES (?, 'Resolved', 1)",
                seed.caseType());
        Actor owner = actor(OWNER, TeamRole.ANALYST);
        number = cases.create(
                owner,
                new CaseFields(
                        seed.caseType(),
                        "Synthetic reopen case",
                        CasePriority.NORMAL,
                        LocalDate.now(ZoneOffset.UTC).minusDays(1),
                        TEAM,
                        OWNER,
                        "Synthetic subject",
                        "Synthetic description",
                        Sensitivity.STANDARD));
        entityManager.flush();
        long id = closures.requestClosure(owner, number.toString(), "Resolved", "A synthetic summary", null, APPROVER);
        decisions.decide(actor(APPROVER, TeamRole.MANAGER), number.toString(), id, ClosureDecision.APPROVED, null);
        reopen = "/cases/" + number + "/reopen";
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @WithAppUser(id = MANAGER, team = TEAM, teamRole = TeamRole.MANAGER)
    void managerIsOfferedReopenOnTheClosedCase() throws Exception {
        assertThat(html(get("/cases/" + number)))
                .containsPattern("href=\"" + reopen + "\"\\s+data-tk-dialog-open=\"tk-reopen-dialog\"")
                .contains("<dialog id=\"tk-reopen-dialog\" class=\"tk-dialog\"", "maxlength=\"1000\"")
                .contains("Closed</li>", "SLA met");
        assertThat(html(get(reopen)))
                .contains("<h1>Reopen " + number + "</h1>", "Back to the case")
                .doesNotContain("data-tk-dialog-focus");
    }

    @Test
    @WithAppUser(id = MANAGER, team = TEAM, teamRole = TeamRole.MANAGER)
    void reopeningStartsTheNextCycleAndShowsReopened() throws Exception {
        mvc.perform(post(reopen).param("reason", "A synthetic reason").with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/cases/" + number));
        // The test's one transaction shares the persistence context across requests; each real request has its own.
        entityManager.flush();
        entityManager.clear();
        assertThat(html(get("/cases/" + number)))
                .contains("Open</li>", "<span>Reopened</span></li>", "SLA: ")
                .contains("href=\"/cases/" + number + "/request-closure\"")
                .doesNotContain("SLA met", "tk-reopen-dialog", "tk-decided-title");
        assertThat(jdbc.queryForObject(
                        "SELECT SLA_CYCLE FROM CASE_SLA WHERE CASE_ID = (SELECT ID FROM CASE_RECORD"
                                + " WHERE CASE_NUMBER = ?)",
                        Integer.class,
                        number.toString()))
                .isEqualTo(2);
        mvc.perform(get(reopen)).andExpect(status().isForbidden());
    }

    @Test
    @WithAppUser(id = MANAGER, team = TEAM, teamRole = TeamRole.MANAGER)
    void missingOrOverlongReasonComesBackMarkedAndSummarized() throws Exception {
        for (String reason : new String[] {" \r\n", "r".repeat(1001)}) {
            String page = mvc.perform(post(reopen).param("reason", reason).with(csrf()))
                    .andExpect(status().isUnprocessableContent())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(page)
                    .contains("data-tk-error-summary", "The case was not reopened.", "href=\"#tk-reopen-reason\"")
                    .contains("id=\"tk-reopen-reason-error\"");
            assertThat(page.split("aria-invalid=\"true\"", -1)).hasSize(2);
        }
        mvc.perform(post(reopen).param("reason", "A synthetic reason")).andExpect(status().isForbidden());
    }

    @Test
    @WithAppUser(id = MANAGER, team = TEAM, teamRole = TeamRole.MANAGER)
    void reopenThatLostTheRaceIsAConflictWithReload() throws Exception {
        // The case stays managed as closed while its row moves on underneath, as a concurrent reopen would move it.
        cases.actionable(actor(MANAGER, TeamRole.MANAGER), number.toString(), CaseAction.REOPEN);
        jdbc.update(
                "UPDATE CASE_RECORD SET STATUS = 'OPEN', CLOSED_AT = NULL WHERE CASE_NUMBER = ?", number.toString());
        assertThat(mvc.perform(post(reopen)
                                .param("reason", "A synthetic reason")
                                .with(csrf()))
                        .andExpect(status().isConflict())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("This case changed since the form was loaded.")
                .contains("href=\"/cases/" + number + "\">Reload</a>", "A synthetic reason");
    }

    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void ownerAnalystIsNotOfferedReopenAndIsRefused() throws Exception {
        assertThat(html(get("/cases/" + number))).doesNotContain("tk-reopen-dialog");
        mvc.perform(get(reopen)).andExpect(status().isForbidden());
        mvc.perform(post(reopen).param("reason", "A synthetic reason").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAppUser(id = OUTSIDER)
    void outsiderGetsNotFound() throws Exception {
        mvc.perform(get(reopen)).andExpect(status().isNotFound());
        mvc.perform(post(reopen).param("reason", "A synthetic reason").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousRequestsGoToTheIdentityProvider() throws Exception {
        mvc.perform(get(reopen)).andExpect(status().isFound());
        mvc.perform(post(reopen).param("reason", "A synthetic reason").with(csrf()))
                .andExpect(status().isFound());
    }

    private static Actor actor(long id, TeamRole role) {
        return new Actor(new UserPermissions(id, false, Set.of(), Map.of(TEAM, role)), "Synthetic Person " + id);
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
