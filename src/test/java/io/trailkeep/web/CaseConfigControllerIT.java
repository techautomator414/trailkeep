// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
import io.trailkeep.user.TeamRole;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The configuration admin endpoints per Part E: the allowed role (Compliance Admin), the nearest disallowed roles
 * (Manager and System Admin, 403), unknown ids (404) and invalid values (422). Permissions come from the database.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CaseConfigControllerIT {

    static final long COMPLIANCE_ADMIN = 910_001L;
    static final long SYSTEM_ADMIN = 910_002L;
    static final long MANAGER = 910_003L;
    static final long POLICY = 910_100L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void seed() {
        // Test-only rows with fixed ids so @WithAppUser can name them.
        user(COMPLIANCE_ADMIN, "Synthetic Compliance Admin", GlobalRole.COMPLIANCE_ADMIN);
        user(SYSTEM_ADMIN, "Synthetic System Admin", GlobalRole.SYSTEM_ADMIN);
        user(MANAGER, "Synthetic Manager", null);
        jdbc.update("INSERT INTO TEAM (ID, NAME, ACTIVE) VALUES (910200, 'Synthetic Config Team', TRUE)");
        jdbc.update(
                "INSERT INTO APP_USER_TEAM (USER_ID, TEAM_ID, ROLE_IN_TEAM) VALUES (?, 910200, 'MANAGER')", MANAGER);
        jdbc.update("INSERT INTO SLA_POLICY (ID, NAME, TARGET_DAYS) VALUES (?, 'Synthetic Policy', 30)", POLICY);
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void complianceAdminsManagePoliciesAndCaseTypesWithAuditEvents() throws Exception {
        mvc.perform(get("/admin/sla-policies"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Synthetic Policy")));
        mvc.perform(get("/admin/sla-policies/{id}", POLICY)).andExpect(status().isOk());
        mvc.perform(policy(post("/admin/sla-policies"), "Synthetic Five", "5"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/admin/sla-policies/*"));
        mvc.perform(caseType(post("/admin/case-types"), "Synthetic Type", "Resolved\r\nDuplicate\r\n"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/admin/case-types/*"));
        mvc.perform(get("/admin/case-types"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Resolved, Duplicate")));
        entityManager.flush();

        assertThat(jdbc.queryForList(
                        "SELECT ENTITY_TYPE FROM AUDIT_EVENT WHERE ACTOR_ID = ? ORDER BY ID",
                        String.class,
                        COMPLIANCE_ADMIN))
                .containsExactly("SLA_POLICY", "CASE_TYPE");
    }

    @Test
    @WithAppUser(id = MANAGER, team = 910_200L, teamRole = TeamRole.MANAGER)
    void managersAreRefused() throws Exception {
        mvc.perform(get("/admin/case-types")).andExpect(status().isForbidden());
        mvc.perform(policy(post("/admin/sla-policies/{id}", POLICY), "Renamed", "9"))
                .andExpect(status().isForbidden());
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT TARGET_DAYS FROM SLA_POLICY WHERE ID = ?", Integer.class, POLICY))
                .isEqualTo(30);
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void systemAdminsAreRefused() throws Exception {
        mvc.perform(get("/admin/sla-policies")).andExpect(status().isForbidden());
        mvc.perform(caseType(post("/admin/case-types"), "Nope", "Resolved")).andExpect(status().isForbidden());
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void unknownIdsAnswerNotFound() throws Exception {
        mvc.perform(get("/admin/sla-policies/{id}", 999_999_999L)).andExpect(status().isNotFound());
        mvc.perform(get("/admin/case-types/{id}", 999_999_999L)).andExpect(status().isNotFound());
        mvc.perform(policy(post("/admin/sla-policies/{id}", 999_999_999L), "Ghost", "5"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void outOfRangeValuesAreUnprocessable() throws Exception {
        mvc.perform(policy(post("/admin/sla-policies"), "Too long", "3651"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("role=\"alert\"")));
        mvc.perform(policy(post("/admin/sla-policies"), "Not a number", "soon"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(caseType(post("/admin/case-types"), "No outcomes", "  \n "))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(caseType(post("/admin/case-types"), "Twice", "Resolved\nResolved"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(post("/admin/case-types")
                        .param("name", "Bad number")
                        .param("slaPolicyId", "x")
                        .with(csrf()))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void mutationsNeedCsrfTokens() throws Exception {
        mvc.perform(post("/admin/sla-policies").param("name", "No token")).andExpect(status().isForbidden());
    }

    private static MockHttpServletRequestBuilder policy(
            MockHttpServletRequestBuilder request, String name, String targetDays) {
        return request.param("name", name)
                .param("anchor", "OPENED_AT")
                .param("targetDays", targetDays)
                .param("atRiskPct", "80")
                .param("overdueReminderDays", "7")
                .param("escalateTo", "OWNER")
                .with(csrf());
    }

    private static MockHttpServletRequestBuilder caseType(
            MockHttpServletRequestBuilder request, String name, String outcomes) {
        return request.param("name", name)
                .param("slaPolicyId", Long.toString(POLICY))
                .param("retentionYears", "7")
                .param("active", "true")
                .param("outcomes", outcomes)
                .with(csrf());
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
