// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.GlobalRole;
import jakarta.persistence.EntityManager;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * The team admin endpoints per Part E: the allowed role (System Admin), the nearest disallowed role (Compliance
 * Admin, 403), unknown teams (404), and the System-Admin-holds-no-team-role invariant (422). Permissions come from the
 * database rows seeded here, not from the test principal's roles.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TeamAdminControllerIT {

    static final long SYSTEM_ADMIN = 900_001L;
    static final long COMPLIANCE_ADMIN = 900_002L;
    static final long ANALYST = 900_003L;
    static final long OTHER_SYSTEM_ADMIN = 900_004L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private long team;

    @BeforeEach
    void seed() {
        // Test-only rows with fixed ids so @WithAppUser can name them; the application assigns ids from sequences.
        user(SYSTEM_ADMIN, "Synthetic System Admin", GlobalRole.SYSTEM_ADMIN);
        user(COMPLIANCE_ADMIN, "Synthetic Compliance Admin", GlobalRole.COMPLIANCE_ADMIN);
        user(ANALYST, "Synthetic Analyst", null);
        user(OTHER_SYSTEM_ADMIN, "Synthetic Second System Admin", GlobalRole.SYSTEM_ADMIN);
        team = 900_100L;
        jdbc.update("INSERT INTO TEAM (ID, NAME, ACTIVE) VALUES (?, 'Synthetic Team', TRUE)", team);
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void systemAdminsSeeTeamsAndTheirMembersScreen() throws Exception {
        mvc.perform(get("/admin/teams"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Synthetic Team")));
        mvc.perform(get("/admin/teams/{id}", team))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Synthetic Analyst")))
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("Synthetic Second System Admin"))));
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void systemAdminsCreateTeamsAndGrantRolesWithAuditEvents() throws Exception {
        mvc.perform(post("/admin/teams")
                        .param("name", "Synthetic New Team")
                        .param("active", "true")
                        .with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/admin/teams/*"));
        mvc.perform(post("/admin/teams/{id}/members", team)
                        .param("userId", Long.toString(ANALYST))
                        .param("role", "ANALYST")
                        .with(csrf()))
                .andExpect(status().isFound());
        entityManager.flush(); // the test transaction spans the requests; JDBC reads see flushed rows only

        assertThat(jdbc.queryForObject(
                        "SELECT ROLE_IN_TEAM FROM APP_USER_TEAM WHERE USER_ID = ? AND TEAM_ID = ?",
                        String.class,
                        ANALYST,
                        team))
                .isEqualTo("ANALYST");
        assertThat(jdbc.queryForList(
                        "SELECT ACTION FROM AUDIT_EVENT WHERE ACTOR_ID = ? ORDER BY ID", String.class, SYSTEM_ADMIN))
                .containsExactly("TEAM_CREATED", "ROLE_GRANTED");
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void complianceAdminsAreTheNearestDisallowedRole() throws Exception {
        mvc.perform(get("/admin/teams")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/teams")
                        .param("name", "Nope")
                        .param("active", "true")
                        .with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/teams/{id}/members", team)
                        .param("userId", Long.toString(ANALYST))
                        .param("role", "MANAGER")
                        .with(csrf()))
                .andExpect(status().isForbidden());
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM APP_USER_TEAM WHERE USER_ID = ?", Integer.class, ANALYST))
                .isZero();
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void unknownTeamsAnswerNotFound() throws Exception {
        mvc.perform(get("/admin/teams/{id}", 999_999_999L)).andExpect(status().isNotFound());
        mvc.perform(post("/admin/teams/{id}/members", 999_999_999L)
                        .param("userId", Long.toString(ANALYST))
                        .param("role", "READER")
                        .with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/admin/teams/{id}/members/{u}/remove", team, ANALYST).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void systemAdminsCannotBeGivenTeamRolesAndBadInputIsUnprocessable() throws Exception {
        mvc.perform(post("/admin/teams/{id}/members", team)
                        .param("userId", Long.toString(OTHER_SYSTEM_ADMIN))
                        .param("role", "READER")
                        .with(csrf()))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(post("/admin/teams/{id}/members", team)
                        .param("role", "READER")
                        .with(csrf()))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(post("/admin/teams")
                        .param("name", " ")
                        .param("active", "true")
                        .with(csrf()))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void mutationsNeedCsrfTokens() throws Exception {
        mvc.perform(post("/admin/teams").param("name", "No token").param("active", "true"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousRequestsGoToTheIdentityProvider() throws Exception {
        mvc.perform(get("/admin/teams")).andExpect(status().isFound());
    }

    @Test
    @WithAppUser(id = ANALYST, globalRole = GlobalRole.SYSTEM_ADMIN)
    void permissionsComeFromTheDatabaseNotFromThePrincipal() throws Exception {
        mvc.perform(get("/admin/teams")).andExpect(status().isForbidden());
    }

    private void user(long id, String name, @Nullable GlobalRole role) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                id,
                "test-subject-" + id,
                name);
        if (role != null) {
            jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, ?)", id, role.name());
        }
    }
}
