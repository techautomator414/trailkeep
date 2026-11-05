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
 * The user admin endpoints per Part E: the allowed role (System Admin), the nearest disallowed role (Compliance Admin,
 * 403), unknown users (404), disabling oneself (422), missing CSRF (403) and anonymous requests (302). Permissions
 * come from the database rows seeded here, not from the test principal's roles.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserAdminControllerIT {

    static final long SYSTEM_ADMIN = 910_001L;
    static final long COMPLIANCE_ADMIN = 910_002L;
    static final long ANALYST = 910_003L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void seed() {
        // Test-only rows with fixed ids so @WithAppUser can name them; the application assigns ids from sequences.
        user(SYSTEM_ADMIN, "Synthetic System Admin", GlobalRole.SYSTEM_ADMIN);
        user(COMPLIANCE_ADMIN, "Synthetic Compliance Admin", GlobalRole.COMPLIANCE_ADMIN);
        user(ANALYST, "Synthetic Analyst", null);
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void systemAdminsSeeADisableButtonForOthersButNotForThemselves() throws Exception {
        mvc.perform(get("/admin/users"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Disable Synthetic Analyst")))
                .andExpect(content().string(not(containsString("Disable Synthetic System Admin"))));
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void systemAdminsDisableAndEnableAUser() throws Exception {
        mvc.perform(post("/admin/users/{id}/disable", ANALYST).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/users"));
        assertThat(disabled(ANALYST)).isTrue();

        mvc.perform(post("/admin/users/{id}/enable", ANALYST).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin/users"));
        assertThat(disabled(ANALYST)).isFalse();
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void complianceAdminsAreTheNearestDisallowedRole() throws Exception {
        mvc.perform(get("/admin/users")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/{id}/disable", ANALYST).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/{id}/enable", ANALYST).with(csrf())).andExpect(status().isForbidden());
        assertThat(disabled(ANALYST)).isFalse();
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void unknownUsersAnswerNotFound() throws Exception {
        mvc.perform(post("/admin/users/{id}/disable", 999_999_999L).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/admin/users/{id}/enable", 999_999_999L).with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void nobodyDisablesThemselves() throws Exception {
        mvc.perform(post("/admin/users/{id}/disable", SYSTEM_ADMIN).with(csrf()))
                .andExpect(status().isUnprocessableContent());
        assertThat(disabled(SYSTEM_ADMIN)).isFalse();
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void mutationsNeedCsrfTokens() throws Exception {
        mvc.perform(post("/admin/users/{id}/disable", ANALYST)).andExpect(status().isForbidden());
        assertThat(disabled(ANALYST)).isFalse();
    }

    @Test
    void anonymousRequestsGoToTheIdentityProvider() throws Exception {
        mvc.perform(get("/admin/users")).andExpect(status().isFound());
    }

    private boolean disabled(long id) {
        entityManager.flush(); // the test transaction spans the requests; JDBC reads see flushed rows only
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT DISABLED FROM APP_USER WHERE ID = ?", Boolean.class, id));
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
