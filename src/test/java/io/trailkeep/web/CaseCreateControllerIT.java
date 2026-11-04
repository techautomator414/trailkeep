// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The create form and {@code POST /cases} per Part E: the allowed role (Analyst) creates and is audited; the nearest
 * disallowed role (Reader) and a System Admin get 403 with valid input and nothing is written; a missing CSRF token
 * is 403; invalid fields and an unconfirmed PHI-looking title re-render the form with 422. There is no case yet, so
 * "unreadable case" does not apply to create; the case page's 404 is {@code CaseControllerIT}'s. Permissions come
 * from seeded database rows.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CaseCreateControllerIT {

    static final long ANALYST = 930_001L;
    static final long READER = 930_002L;
    static final long SYSTEM_ADMIN = 930_003L;
    static final long TEAM = 930_100L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private long caseType;

    @BeforeEach
    void seed() {
        user(ANALYST, "Synthetic Create Analyst", null);
        user(READER, "Synthetic Create Reader", null);
        user(SYSTEM_ADMIN, "Synthetic Create System Admin", GlobalRole.SYSTEM_ADMIN);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Create Team')", TEAM);
        CaseSeed seed = new CaseSeed(jdbc);
        seed.member(ANALYST, TEAM, TeamRole.ANALYST);
        seed.member(READER, TEAM, TeamRole.READER);
        caseType = seed.caseType();
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void analystsSeeTheirTeamAndCreateAnAuditedCase() throws Exception {
        mvc.perform(get("/cases/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Synthetic Create Team")))
                .andExpect(content().string(containsString("Synthetic Create Analyst")))
                .andExpect(content().string(not(containsString("Synthetic Create Reader"))))
                .andExpect(content().string(containsString("may have gaps")));

        MvcResult created = mvc.perform(form("Synthetic created case"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/cases/CASE-*?created"))
                .andReturn();
        String location = String.valueOf(created.getResponse().getRedirectedUrl());
        String casePage = location.substring(0, location.indexOf('?'));
        mvc.perform(get(casePage))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("data-tk-toast"))));
        // MockMvc drops a value-less query parameter, which a browser sends as "created=" (GoldenPathOneIT).
        mvc.perform(get(casePage).queryParam("created", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-tk-toast")))
                .andExpect(content().string(containsString("was created")));
        entityManager.flush();

        assertThat(jdbc.queryForList(
                        "SELECT ACTION FROM AUDIT_EVENT WHERE ACTOR_ID = ? AND ENTITY_TYPE = 'CASE'",
                        String.class,
                        ANALYST))
                .containsExactly("CASE_CREATED");
    }

    @Test
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void readersAreToldTheyCannotCreateAndAreRefused() throws Exception {
        mvc.perform(get("/cases/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("You cannot create cases")))
                .andExpect(content().string(not(containsString("action=\"/cases\""))));
        mvc.perform(form("Synthetic reader case")).andExpect(status().isForbidden());
        assertNothingCreated();
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void systemAdminsAreRefused() throws Exception {
        mvc.perform(form("Synthetic admin case")).andExpect(status().isForbidden());
        assertNothingCreated();
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void mutationsNeedCsrfTokens() throws Exception {
        mvc.perform(post("/cases").param("title", "No token")).andExpect(status().isForbidden());
        assertNothingCreated();
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void invalidFieldsReRenderTheFormWithAnErrorSummary() throws Exception {
        mvc.perform(form(" "))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("data-tk-error-summary")))
                .andExpect(content().string(containsString("href=\"#title\"")))
                .andExpect(content().string(containsString("aria-describedby=\"title-error title-hint\"")))
                .andExpect(content().string(containsString("Synthetic description")));
        mvc.perform(form("Synthetic no type", "", today()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("href=\"#caseTypeId\"")))
                .andExpect(content().string(containsString("aria-describedby=\"caseTypeId-error\"")));
        mvc.perform(form(
                        "Future",
                        Long.toString(caseType),
                        LocalDate.now(ZoneOffset.UTC).plusDays(2).toString()))
                .andExpect(status().isUnprocessableContent());
        assertNothingCreated();
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void titlesThatLookLikePhiNeedConfirmation() throws Exception {
        mvc.perform(form("Synthetic 123-45-6789"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("looks like a social security number")))
                .andExpect(content().string(containsString("name=\"titleConfirmed\"")))
                .andExpect(content().string(containsString("value=\"Synthetic 123-45-6789\"")));
        assertNothingCreated();

        mvc.perform(form("Synthetic 123-45-6789").param("titleConfirmed", "true"))
                .andExpect(status().isFound());
    }

    private MockHttpServletRequestBuilder form(String title) {
        return form(title, Long.toString(caseType), today());
    }

    private static MockHttpServletRequestBuilder form(String title, String caseTypeId, String identifiedOn) {
        return post("/cases")
                .param("caseTypeId", caseTypeId)
                .param("title", title)
                .param("priority", "NORMAL")
                .param("identifiedOn", identifiedOn)
                .param("teamId", Long.toString(TEAM))
                .param("ownerId", Long.toString(ANALYST))
                .param("subjectText", "")
                .param("description", "Synthetic description")
                .param("sensitivity", "STANDARD")
                .with(csrf());
    }

    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private void assertNothingCreated() {
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM CASE_RECORD WHERE TEAM_ID = ?", Long.class, TEAM))
                .isZero();
    }

    private void user(long id, String name, @Nullable GlobalRole role) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                id,
                "create-subject-" + id,
                name);
        if (role != null) {
            jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, ?)", id, role.name());
        }
    }
}
