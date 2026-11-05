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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.casefile.CaseChanges;
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
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
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
 * Edit per Part E (WP-12): the allowed role (Analyst) edits, is audited and sees a one-shot notice; the nearest
 * disallowed role (Reader) gets 403 on the form and the save and is not offered Edit; a case the person may not read
 * answers 404 to both, even with invalid input; a missing CSRF token is 403; a stale version is 409 with the input
 * kept and Reload offered; invalid fields and an unconfirmed PHI-looking new title are 422. Permissions come from
 * seeded database rows.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CaseEditControllerIT {

    static final long ANALYST = 940_001L;
    static final long READER = 940_002L;
    static final long OTHER_MANAGER = 940_003L;
    static final long TEAM = 940_100L;
    static final long OTHER_TEAM = 940_101L;

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
        user(ANALYST, "Synthetic Edit Analyst");
        user(READER, "Synthetic Edit Reader");
        user(OTHER_MANAGER, "Synthetic Edit Other Manager");
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Edit Team')", TEAM);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Other Edit Team')", OTHER_TEAM);
        CaseSeed seed = new CaseSeed(jdbc);
        seed.member(ANALYST, TEAM, TeamRole.ANALYST);
        seed.member(READER, TEAM, TeamRole.READER);
        seed.member(OTHER_MANAGER, OTHER_TEAM, TeamRole.MANAGER);
        number = cases.create(
                analyst(),
                new CaseFields(
                        seed.caseType(),
                        "Synthetic edit case",
                        CasePriority.NORMAL,
                        LocalDate.now(ZoneOffset.UTC).minusDays(1),
                        TEAM,
                        ANALYST,
                        "Synthetic subject",
                        "Synthetic description",
                        Sensitivity.STANDARD));
        entityManager.flush();
    }

    @Test
    @WithAppUser(id = ANALYST, name = "Synthetic Edit Analyst", team = TEAM, teamRole = TeamRole.ANALYST)
    void analystsEditAuditAndSeeTheNoticeOnce() throws Exception {
        mvc.perform(get("/cases/" + number))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/cases/" + number + "/edit\"")));
        mvc.perform(get("/cases/" + number + "/edit"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Edit " + number)))
                .andExpect(content().string(containsString("value=\"Synthetic edit case\"")))
                .andExpect(content().string(containsString("name=\"version\" value=\"0\"")))
                .andExpect(content().string(containsString("data-tk-ctrl-s=\"true\"")));

        Cookie session = Objects.requireNonNull(mvc.perform(save("Synthetic edited title", 0))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/cases/" + number))
                .andReturn()
                .getResponse()
                .getCookie("SESSION"));
        mvc.perform(get("/cases/" + number).cookie(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Synthetic edited title")))
                .andExpect(content().string(containsString("Your changes were saved.")));
        mvc.perform(get("/cases/" + number).cookie(session))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Your changes were saved."))));
        entityManager.flush();

        assertThat(jdbc.queryForList(
                        "SELECT ACTION FROM AUDIT_EVENT WHERE ACTOR_ID = ? AND ENTITY_TYPE = 'CASE' ORDER BY ID",
                        String.class,
                        ANALYST))
                .containsExactly("CASE_CREATED", "CASE_UPDATED");
    }

    @Test
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void readersAreNotOfferedEditAndAreRefused() throws Exception {
        mvc.perform(get("/cases/" + number))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/edit\""))));
        mvc.perform(get("/cases/" + number + "/edit")).andExpect(status().isForbidden());
        mvc.perform(save("Synthetic reader title", 0)).andExpect(status().isForbidden());
        assertTitle("Synthetic edit case");
    }

    @Test
    @WithAppUser(id = OTHER_MANAGER, team = OTHER_TEAM, teamRole = TeamRole.MANAGER)
    void unreadableCasesAreNotFoundEvenWithInvalidInput() throws Exception {
        mvc.perform(get("/cases/" + number + "/edit")).andExpect(status().isNotFound());
        mvc.perform(save("Synthetic probe", 0)).andExpect(status().isNotFound());
        mvc.perform(save(" ", 0)).andExpect(status().isNotFound());
        mvc.perform(save("Synthetic probe", "nope")).andExpect(status().isNotFound());
        assertTitle("Synthetic edit case");
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void savesNeedCsrfTokens() throws Exception {
        mvc.perform(post("/cases/" + number + "/edit")
                        .param("title", "No token")
                        .param("priority", "NORMAL")
                        .param("description", "Synthetic description")
                        .param("version", "0"))
                .andExpect(status().isForbidden());
        assertTitle("Synthetic edit case");
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void staleVersionsAreConflictsThatKeepTheInputAndOfferReload() throws Exception {
        cases.edit(
                analyst(),
                number.toString(),
                0,
                new CaseChanges("Saved first", CasePriority.HIGH, null, "Synthetic description"));
        entityManager.flush();

        mvc.perform(save("My unsaved synthetic title", 0))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("This case was updated by Synthetic Edit Analyst at")))
                .andExpect(content().string(containsString("value=\"My unsaved synthetic title\"")))
                .andExpect(content().string(containsString("href=\"/cases/" + number + "/edit\"")))
                .andExpect(content().string(containsString("data-tk-error-summary")))
                .andExpect(content().string(not(containsString("data-tk-ctrl-s"))))
                .andExpect(content().string(not(containsString("Save changes"))));
        assertTitle("Saved first");
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void invalidFieldsAndUnconfirmedPhiTitlesAreUnprocessable() throws Exception {
        mvc.perform(save(" ", 0))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("Your changes were not saved. Correct these fields")))
                .andExpect(content().string(containsString("href=\"#title\"")))
                .andExpect(content().string(containsString("aria-describedby=\"title-error title-hint\"")));
        mvc.perform(save("Synthetic 123-45-6789", 0))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("looks like a social security number")))
                .andExpect(content().string(containsString("name=\"titleConfirmed\"")));
        assertTitle("Synthetic edit case");

        mvc.perform(save("Synthetic 123-45-6789", 0).param("titleConfirmed", "true"))
                .andExpect(status().isFound());
        assertTitle("Synthetic 123-45-6789");
    }

    private MockHttpServletRequestBuilder save(String title, int version) {
        return save(title, Integer.toString(version));
    }

    private MockHttpServletRequestBuilder save(String title, String version) {
        return post("/cases/" + number + "/edit")
                .param("title", title)
                .param("priority", "HIGH")
                .param("subjectText", "")
                .param("description", "Synthetic edited description")
                .param("version", version)
                .with(csrf());
    }

    private void assertTitle(String title) {
        entityManager.flush();
        assertThat(jdbc.queryForObject(
                        "SELECT TITLE FROM CASE_RECORD WHERE CASE_NUMBER = ?", String.class, number.toString()))
                .isEqualTo(title);
    }

    private static Actor analyst() {
        return actor(ANALYST, TeamRole.ANALYST);
    }

    private static Actor actor(long id, TeamRole role) {
        return new Actor(new UserPermissions(id, false, Set.of(), Map.of(TEAM, role)), "Synthetic Edit Analyst");
    }

    private void user(long id, String name) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                id,
                "edit-subject-" + id,
                name);
    }
}
