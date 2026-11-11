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
import io.trailkeep.user.ImportPeopleService;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
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
 * Request Update over HTTP (WP-18 part b): the case page offers "Request update" (a dialog opener that is also a link
 * to the same form as a page) only to people who may ask, with a native select of exactly the people who can read
 * the case, never the actor or a disabled person; the POST needs CSRF (403), answers 422 and 409 on the form page,
 * 403 for a Reader and 404 for an outsider, and redirects with a toast otherwise; the case page then lists the open
 * request with "Mark answered" for its requester and a Manager only; the recipient's dashboard lists it under
 * "Needs attention". Anonymous requests go to the identity provider.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UpdateRequestControllerIT {

    static final long OWNER = 985_001L;
    static final long COLLEAGUE = 985_002L;
    static final long READER = 985_003L;
    static final long MANAGER = 985_004L;
    static final long DISABLED = 985_005L;
    static final long OUTSIDER = 985_006L;
    static final long TEAM = 985_100L;

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
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Request Team')", TEAM);
        CaseSeed seed = new CaseSeed(jdbc);
        for (long id : new long[] {OWNER, COLLEAGUE, READER, MANAGER, DISABLED, OUTSIDER}) {
            jdbc.update(
                    "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                            + " VALUES (?, ?, ?, 'user@example.invalid', ?, CURRENT_TIMESTAMP)",
                    id,
                    "request-subject-" + id,
                    "Synthetic Person " + id,
                    id == DISABLED);
        }
        seed.member(OWNER, TEAM, TeamRole.ANALYST);
        seed.member(COLLEAGUE, TEAM, TeamRole.ANALYST);
        seed.member(READER, TEAM, TeamRole.READER);
        seed.member(MANAGER, TEAM, TeamRole.MANAGER);
        seed.member(DISABLED, TEAM, TeamRole.ANALYST);
        number = cases.create(
                new Actor(new UserPermissions(OWNER, false, Set.of(), Map.of(TEAM, TeamRole.ANALYST)), "Owner"),
                new CaseFields(
                        seed.caseType(),
                        "Synthetic request case",
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
    void theCasePageOffersTheDialogWithOnlyPeopleWhoCanReadTheCase() throws Exception {
        String page = html(get("/cases/" + number));
        assertThat(page)
                .containsPattern("<a class=\"btn btn-outline-primary\"\\s+href=\"/cases/" + number + "/request-update\""
                        + " data-tk-dialog-open=\"tk-request-dialog\"\\s+aria-haspopup=\"dialog\">Request update</a>")
                .contains("<dialog id=\"tk-request-dialog\" class=\"tk-dialog\" aria-labelledby=\"tk-request-title\">")
                .containsPattern("<select class=\"form-select\" id=\"tk-recipient\" name=\"recipientId\" required"
                        + " data-tk-combobox\\s+aria-describedby=\"tk-recipient-hint\" data-tk-dialog-focus=\"true\">")
                .contains(option(COLLEAGUE), option(READER), option(MANAGER))
                .doesNotContain(option(OWNER), option(DISABLED), option(OUTSIDER));
        assertThat(html(get("/cases/" + number + "/request-update")))
                .contains("<h1>Request an update on " + number + "</h1>", option(COLLEAGUE), "Back to the case")
                .doesNotContain("data-tk-dialog-focus");
    }

    @Test
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void readerIsNotOfferedTheRequestAndIsRefused() throws Exception {
        assertThat(html(get("/cases/" + number))).doesNotContain("tk-request-dialog", "Request update");
        mvc.perform(post("/cases/" + number + "/request-update")
                        .param("recipientId", String.valueOf(COLLEAGUE))
                        .param("message", "x")
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAppUser(id = OUTSIDER)
    void outsiderGetsNotFound() throws Exception {
        mvc.perform(get("/cases/" + number + "/request-update")).andExpect(status().isNotFound());
        mvc.perform(post("/cases/" + number + "/request-update")
                        .param("recipientId", String.valueOf(COLLEAGUE))
                        .param("message", "x")
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void requestingRedirectsWithToastAndRefusalsComeBackOnTheFormPage() throws Exception {
        mvc.perform(send(String.valueOf(COLLEAGUE), "A synthetic question")).andExpect(status().isForbidden());
        mvc.perform(send(String.valueOf(OUTSIDER), "A synthetic question").with(csrf()))
                .andExpect(status().isUnprocessableContent());
        assertThat(mvc.perform(send("not-an-id", "A synthetic question").with(csrf()))
                        .andExpect(status().isUnprocessableContent())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("data-tk-error-summary", "Choose a person from the list")
                .contains(">A synthetic question</textarea>");

        mvc.perform(send(String.valueOf(COLLEAGUE), "A synthetic question").with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/cases/" + number));
        assertThat(mvc.perform(send(String.valueOf(COLLEAGUE), "Again").with(csrf()))
                        .andExpect(status().isConflict())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("An update is already requested from this person on this case.");

        assertThat(html(get("/cases/" + number)))
                .contains("<h2 id=\"update-requests\">Awaiting an update</h2>", "A synthetic question")
                .contains("Update requested from Synthetic Person " + COLLEAGUE)
                .containsPattern("action=\"/cases/" + number + "/update-requests/\\d+/answered\"")
                .contains("class=\"visually-hidden\">: Synthetic Person " + COLLEAGUE + "</span>");
    }

    /**
     * The {@code -1} a malformed id becomes, and the import principals' ids (ADR-0015), are never a recipient: no row
     * has {@code -1}, and the principals read no case.
     */
    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void theMalformedIdSentinelAndThePrincipalsAreRefusedAsRecipients() throws Exception {
        for (long recipient : List.of(-1L, ImportPeopleService.IMPORTER_ID, ImportPeopleService.LEGACY_APPROVAL_ID)) {
            mvc.perform(send(String.valueOf(recipient), "A synthetic question").with(csrf()))
                    .andExpect(status().isUnprocessableContent());
        }
    }

    @Test
    @WithAppUser(id = COLLEAGUE, team = TEAM, teamRole = TeamRole.ANALYST)
    void markAnsweredIsOfferedToTheRequesterAndManagersOnlyAndTheRecipientSeesItOnTheDashboard() throws Exception {
        long id = ownerAsks(READER);
        assertThat(html(get("/cases/" + number)))
                .contains("Awaiting an update")
                .doesNotContain("/update-requests/" + id + "/answered");
        mvc.perform(post("/cases/" + number + "/update-requests/" + id + "/answered")
                        .with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/cases/" + number + "/update-requests/not-an-id/answered")
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void theRecipientsDashboardListsTheRequestUnderNeedsAttention() throws Exception {
        ownerAsks(READER);
        assertThat(html(get("/")))
                .contains("<h2 id=\"tk-attention\">Needs attention</h2>")
                .contains("<a href=\"/cases/" + number + "#comments\">Synthetic Person " + OWNER
                        + " asked you for an update on " + number + ", Synthetic request case</a>");
    }

    @Test
    @WithAppUser(id = MANAGER, team = TEAM, teamRole = TeamRole.MANAGER)
    void managerMarksSomeoneElsesRequestAnsweredWithCsrf() throws Exception {
        long id = ownerAsks(COLLEAGUE);
        assertThat(html(get("/cases/" + number))).contains("/update-requests/" + id + "/answered");
        mvc.perform(post("/cases/" + number + "/update-requests/" + id + "/answered"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/cases/" + number + "/update-requests/" + id + "/answered")
                        .with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/cases/" + number));
        // The test's one transaction shares the persistence context across requests; each real request has its own.
        entityManager.clear();
        assertThat(html(get("/cases/" + number))).doesNotContain("Awaiting an update");
    }

    @Test
    void anonymousRequestsGoToTheIdentityProvider() throws Exception {
        mvc.perform(get("/cases/" + number + "/request-update")).andExpect(status().isFound());
        mvc.perform(send(String.valueOf(COLLEAGUE), "x").with(csrf())).andExpect(status().isFound());
    }

    private long ownerAsks(long recipient) {
        jdbc.update(
                "INSERT INTO UPDATE_REQUEST (ID, CASE_ID, REQUESTED_BY, REQUESTED_FROM, MESSAGE, REQUESTED_AT,"
                        + " OPEN_KEY) SELECT 985200, ID, ?, ?, 'A synthetic question', CURRENT_TIMESTAMP,"
                        + " ID || ':' || ?"
                        + " FROM CASE_RECORD WHERE CASE_NUMBER = ?",
                OWNER,
                recipient,
                recipient,
                number.toString());
        return 985_200L;
    }

    private MockHttpServletRequestBuilder send(String recipient, String message) {
        return post("/cases/" + number + "/request-update")
                .param("recipientId", recipient)
                .param("message", message);
    }

    private static String option(long id) {
        return "<option value=\"" + id + "\">Synthetic Person " + id + "</option>";
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
