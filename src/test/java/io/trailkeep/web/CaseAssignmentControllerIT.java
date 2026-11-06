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
import io.trailkeep.shared.CaseStatus;
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
import java.util.List;
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
 * Reassign, Mark restricted and Mark standard per Part E (WP-12). For each POST: the allowed role succeeds, is
 * audited and leaves a one-shot notice; the nearest disallowed role gets 403 (an Analyst who does not own the case
 * for Reassign and Mark restricted, the Analyst-owner for Mark standard); a case the person may not read answers 404
 * even with invalid input and a stale version; a missing CSRF token is 403; a stale version is 409 with Reload. An
 * owner outside the chosen team, or a team the actor may not open cases in, is 422. Reassign is refused while a
 * closure request is pending. Permissions come from seeded database rows.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CaseAssignmentControllerIT {

    static final long ANALYST = 950_001L;
    static final long COLLEAGUE = 950_002L;
    static final long MANAGER = 950_003L;
    static final long OTHER_MANAGER = 950_004L;
    static final long SECOND_TEAM_ANALYST = 950_005L;
    static final long TEAM = 950_100L;
    static final long SECOND_TEAM = 950_101L;
    static final long OTHER_TEAM = 950_102L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CaseService cases;

    @Autowired
    private EntityManager entityManager;

    private CaseSeed seed;
    private CaseNumber number;
    private CaseNumber restricted;

    @BeforeEach
    void seed() {
        user(ANALYST, "Synthetic Assign Analyst");
        user(COLLEAGUE, "Synthetic Assign Colleague");
        user(MANAGER, "Synthetic Assign Manager");
        user(OTHER_MANAGER, "Synthetic Assign Other Manager");
        user(SECOND_TEAM_ANALYST, "Synthetic Second Team Analyst");
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Assign Team')", TEAM);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Second Assign Team')", SECOND_TEAM);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Other Assign Team')", OTHER_TEAM);
        seed = new CaseSeed(jdbc);
        seed.member(ANALYST, TEAM, TeamRole.ANALYST);
        seed.member(ANALYST, SECOND_TEAM, TeamRole.ANALYST);
        seed.member(COLLEAGUE, TEAM, TeamRole.ANALYST);
        seed.member(MANAGER, TEAM, TeamRole.MANAGER);
        seed.member(OTHER_MANAGER, OTHER_TEAM, TeamRole.MANAGER);
        seed.member(SECOND_TEAM_ANALYST, SECOND_TEAM, TeamRole.ANALYST);
        number = cases.create(actor(ANALYST), fields(Sensitivity.STANDARD));
        restricted = cases.create(actor(ANALYST), fields(Sensitivity.RESTRICTED));
        entityManager.flush();
    }

    @Test
    @WithAppUser(id = ANALYST, name = "Synthetic Assign Analyst", team = TEAM, teamRole = TeamRole.ANALYST)
    void analystOwnersReassignAndSeeTheNoticeOnce() throws Exception {
        mvc.perform(get("/cases/" + number))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-tk-dialog-open=\"tk-reassign-dialog\"")))
                .andExpect(content().string(containsString("<dialog id=\"tk-reassign-dialog\"")))
                .andExpect(content().string(containsString("href=\"/cases/" + number + "/restrict\"")))
                .andExpect(content().string(not(containsString("/unrestrict\""))));
        mvc.perform(get("/cases/" + number + "/reassign"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Reassign " + number)))
                .andExpect(content().string(containsString("Synthetic Second Assign Team")))
                .andExpect(content().string(not(containsString("Synthetic Other Assign Team"))));

        Cookie session = Objects.requireNonNull(mvc.perform(reassign(number, SECOND_TEAM, ANALYST, "0"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/cases/" + number))
                .andReturn()
                .getResponse()
                .getCookie("SESSION"));
        mvc.perform(get("/cases/" + number).cookie(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Synthetic Second Assign Team")))
                .andExpect(content().string(containsString("The case was reassigned.")));
        mvc.perform(get("/cases/" + number).cookie(session))
                .andExpect(content().string(not(containsString("The case was reassigned."))));
        assertThat(actions(number)).containsExactly("CASE_CREATED", "CASE_REASSIGNED");
    }

    @Test
    @WithAppUser(id = COLLEAGUE, team = TEAM, teamRole = TeamRole.ANALYST)
    void analystsWhoDoNotOwnTheCaseAreNotOfferedTheActionsAndAreRefused() throws Exception {
        mvc.perform(get("/cases/" + number))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("dialog-open=\"tk-"))))
                .andExpect(content().string(not(containsString("/reassign\""))));
        mvc.perform(get("/cases/" + number + "/reassign")).andExpect(status().isForbidden());
        mvc.perform(reassign(number, TEAM, COLLEAGUE, "0")).andExpect(status().isForbidden());
        mvc.perform(get("/cases/" + number + "/restrict")).andExpect(status().isForbidden());
        mvc.perform(change(number, "restrict", "0")).andExpect(status().isForbidden());
        assertThat(actions(number)).containsExactly("CASE_CREATED");
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void analystOwnersMayNotMarkTheirRestrictedCaseStandard() throws Exception {
        mvc.perform(get("/cases/" + restricted))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/unrestrict\""))));
        mvc.perform(get("/cases/" + restricted + "/unrestrict")).andExpect(status().isForbidden());
        mvc.perform(change(restricted, "unrestrict", "0")).andExpect(status().isForbidden());
        assertThat(sensitivity(restricted)).isEqualTo("RESTRICTED");
    }

    @Test
    @WithAppUser(id = OTHER_MANAGER, team = OTHER_TEAM, teamRole = TeamRole.MANAGER)
    void unreadableCasesAreNotFoundEvenWithInvalidInput() throws Exception {
        mvc.perform(get("/cases/" + number + "/reassign")).andExpect(status().isNotFound());
        mvc.perform(reassign(number, OTHER_TEAM, OTHER_MANAGER, "0")).andExpect(status().isNotFound());
        mvc.perform(reassign(number, -5, -5, "nope")).andExpect(status().isNotFound());
        mvc.perform(get("/cases/" + number + "/restrict")).andExpect(status().isNotFound());
        mvc.perform(change(number, "restrict", "99")).andExpect(status().isNotFound());
        mvc.perform(change(restricted, "unrestrict", "nope")).andExpect(status().isNotFound());
        mvc.perform(change("CASE-2026-999999", "restrict", "0")).andExpect(status().isNotFound());
        assertThat(actions(number)).containsExactly("CASE_CREATED");
    }

    @Test
    @WithAppUser(id = MANAGER, team = TEAM, teamRole = TeamRole.MANAGER)
    void postsNeedCsrfTokens() throws Exception {
        mvc.perform(post("/cases/" + number + "/reassign")
                        .param("teamId", Long.toString(TEAM))
                        .param("ownerId", Long.toString(MANAGER))
                        .param("version", "0"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/cases/" + number + "/restrict").param("version", "0"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/cases/" + restricted + "/unrestrict").param("version", "0"))
                .andExpect(status().isForbidden());
        assertThat(actions(number)).containsExactly("CASE_CREATED");
        assertThat(sensitivity(restricted)).isEqualTo("RESTRICTED");
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void staleVersionsAreConflictsThatOfferReload() throws Exception {
        editAs(ANALYST, number);

        mvc.perform(reassign(number, TEAM, COLLEAGUE, "0"))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("This case was changed since you opened the form")))
                .andExpect(content().string(containsString("href=\"/cases/" + number + "/reassign\"")))
                .andExpect(content().string(containsString("data-tk-error-summary")))
                .andExpect(content().string(not(containsString("name=\"ownerId\""))));
        mvc.perform(change(number, "restrict", "0"))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("This case was changed since you opened the confirmation")))
                .andExpect(content().string(containsString("href=\"/cases/" + number + "\"")));
        assertThat(actions(number)).containsExactly("CASE_CREATED", "CASE_UPDATED");
        assertThat(sensitivity(number)).isEqualTo("STANDARD");
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void ownersOutsideTheTeamAndTeamsTheActorMayNotOpenCasesInAreUnprocessable() throws Exception {
        mvc.perform(reassign(number, TEAM, SECOND_TEAM_ANALYST, "0"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("The case was not reassigned.")))
                .andExpect(content().string(containsString("href=\"#ownerId\"")))
                .andExpect(content().string(containsString("aria-describedby=\"ownerId-error owner-hint\"")));
        mvc.perform(reassign(number, OTHER_TEAM, OTHER_MANAGER, "0")).andExpect(status().isUnprocessableContent());
        mvc.perform(reassign(number, TEAM, -1, "0")).andExpect(status().isUnprocessableContent());
        assertThat(actions(number)).containsExactly("CASE_CREATED");
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void analystOwnersRestrictAndKeepReadingUntilTheyReassignTheCaseAway() throws Exception {
        Cookie session = Objects.requireNonNull(mvc.perform(change(number, "restrict", "0"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/cases/" + number))
                .andReturn()
                .getResponse()
                .getCookie("SESSION"));
        mvc.perform(get("/cases/" + number).cookie(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The case is now restricted.")))
                .andExpect(content().string(not(containsString("/restrict\""))));
        assertThat(sensitivity(number)).isEqualTo("RESTRICTED");

        // Reassigned to a colleague, the restricted case leaves the Analyst's scope: back to the dashboard.
        mvc.perform(reassign(number, TEAM, COLLEAGUE, "1")).andExpect(redirectedUrl("/"));
        mvc.perform(get("/cases/" + number)).andExpect(status().isNotFound());
        // The one full page view of the now restricted case is audited (#82); the 404 afterwards is not.
        assertThat(actions(number))
                .containsExactly("CASE_CREATED", "CASE_RESTRICTED", "VIEW_RESTRICTED", "CASE_REASSIGNED");
    }

    @Test
    @WithAppUser(id = MANAGER, team = TEAM, teamRole = TeamRole.MANAGER)
    void managersMarkRestrictedCasesStandard() throws Exception {
        mvc.perform(get("/cases/" + restricted))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-tk-dialog-open=\"tk-STANDARD-dialog\"")));
        mvc.perform(get("/cases/" + restricted + "/unrestrict"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Mark " + restricted + " standard")));
        editAs(MANAGER, restricted);
        mvc.perform(change(restricted, "unrestrict", "0")).andExpect(status().isConflict());

        mvc.perform(change(restricted, "unrestrict", "1")).andExpect(redirectedUrl("/cases/" + restricted));
        assertThat(sensitivity(restricted)).isEqualTo("STANDARD");
        // The Manager's page view of the restricted case is audited (#82); the unrestrict form page is not a case view.
        assertThat(actions(restricted))
                .containsExactly("CASE_CREATED", "VIEW_RESTRICTED", "CASE_UPDATED", "CASE_UNRESTRICTED");
    }

    @Test
    @WithAppUser(id = MANAGER, team = TEAM, teamRole = TeamRole.MANAGER)
    void reassignIsRefusedWhileAClosureRequestIsPending() throws Exception {
        CaseNumber pending = seed.caseRow(TEAM, ANALYST, Sensitivity.STANDARD, CaseStatus.PENDING_CLOSURE);
        mvc.perform(get("/cases/" + pending))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/reassign\""))));
        mvc.perform(get("/cases/" + pending + "/reassign")).andExpect(status().isForbidden());
        mvc.perform(reassign(pending, TEAM, MANAGER, "0")).andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder reassign(CaseNumber target, long teamId, long ownerId, String version) {
        return post("/cases/" + target + "/reassign")
                .param("teamId", Long.toString(teamId))
                .param("ownerId", Long.toString(ownerId))
                .param("version", version)
                .with(csrf());
    }

    private MockHttpServletRequestBuilder change(CaseNumber target, String change, String version) {
        return change(target.toString(), change, version);
    }

    private MockHttpServletRequestBuilder change(String target, String change, String version) {
        return post("/cases/" + target + "/" + change).param("version", version).with(csrf());
    }

    /** Someone saves an edit first, so version 0 is stale. */
    private void editAs(long userId, CaseNumber target) {
        cases.edit(
                new Actor(new UserPermissions(userId, false, Set.of(), Map.of(TEAM, TeamRole.MANAGER)), "Someone"),
                target.toString(),
                0,
                new CaseChanges("Saved first", CasePriority.HIGH, null, "Synthetic description"));
        entityManager.flush();
    }

    private List<String> actions(CaseNumber target) {
        entityManager.flush();
        return jdbc.queryForList(
                "SELECT ACTION FROM AUDIT_EVENT WHERE CASE_ID = ? ORDER BY ID", String.class, seed.caseId(target));
    }

    private String sensitivity(CaseNumber target) {
        entityManager.flush();
        return String.valueOf(jdbc.queryForObject(
                "SELECT SENSITIVITY FROM CASE_RECORD WHERE CASE_NUMBER = ?", String.class, target.toString()));
    }

    private static Actor actor(long id) {
        return new Actor(
                new UserPermissions(id, false, Set.of(), Map.of(TEAM, TeamRole.ANALYST)), "Synthetic Assign Analyst");
    }

    private CaseFields fields(Sensitivity sensitivity) {
        return new CaseFields(
                seed.caseType(),
                "Synthetic assign case",
                CasePriority.NORMAL,
                LocalDate.now(ZoneOffset.UTC).minusDays(1),
                TEAM,
                ANALYST,
                "Synthetic subject",
                "Synthetic description",
                sensitivity);
    }

    private void user(long id, String name) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                id,
                "assign-subject-" + id,
                name);
    }
}
