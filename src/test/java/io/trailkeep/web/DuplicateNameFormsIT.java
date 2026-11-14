// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.GlobalRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every admin form whose entity has a unique name (SLA policy, case type, team), create and rename: submitting a name
 * another row already has is answered 422 with the form shown again, an {@code aria-invalid} field and a message tied
 * to it, and nothing is changed. It is never a 409 error page and never a 500 (issue 213).
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DuplicateNameFormsIT {

    static final long COMPLIANCE_ADMIN = 920_001L;
    static final long SYSTEM_ADMIN = 920_002L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void seed() {
        user(COMPLIANCE_ADMIN, "Synthetic Compliance Admin", GlobalRole.COMPLIANCE_ADMIN);
        user(SYSTEM_ADMIN, "Synthetic System Admin", GlobalRole.SYSTEM_ADMIN);
        jdbc.update("INSERT INTO SLA_POLICY (ID, NAME, TARGET_DAYS) VALUES (920101, 'Taken Policy', 30)");
        jdbc.update("INSERT INTO SLA_POLICY (ID, NAME, TARGET_DAYS) VALUES (920102, 'Other Policy', 30)");
        jdbc.update("INSERT INTO CASE_TYPE (ID, NAME, SLA_POLICY_ID, RETENTION_YEARS, ACTIVE)"
                + " VALUES (920201, 'Taken Type', 920101, 7, TRUE)");
        jdbc.update("INSERT INTO CASE_TYPE (ID, NAME, SLA_POLICY_ID, RETENTION_YEARS, ACTIVE)"
                + " VALUES (920202, 'Other Type', 920101, 7, TRUE)");
        jdbc.update("INSERT INTO TEAM (ID, NAME, ACTIVE) VALUES (920301, 'Taken Team', TRUE)");
        jdbc.update("INSERT INTO TEAM (ID, NAME, ACTIVE) VALUES (920302, 'Other Team', TRUE)");
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void creatingAPolicyWithATakenNameIsAFieldError() throws Exception {
        fieldError(mvc.perform(policy(post("/admin/sla-policies"), "Taken Policy")), "An SLA policy with this name");
        entityManager.flush();
        assertThat(count("SLA_POLICY", "Taken Policy")).isEqualTo(1);
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void renamingAPolicyToATakenNameIsAFieldErrorThatKeepsWhatWasTyped() throws Exception {
        fieldError(
                        mvc.perform(policy(post("/admin/sla-policies/{id}", 920_102L), "Taken Policy")),
                        "An SLA policy with this name")
                .andExpect(content().string(containsString("value=\"Taken Policy\"")));
        entityManager.flush();
        assertThat(count("SLA_POLICY", "Other Policy")).isEqualTo(1);
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void creatingACaseTypeWithATakenNameIsAFieldError() throws Exception {
        fieldError(mvc.perform(caseType(post("/admin/case-types"), "Taken Type")), "A case type with this name");
        entityManager.flush();
        assertThat(count("CASE_TYPE", "Taken Type")).isEqualTo(1);
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void renamingACaseTypeToATakenNameIsAFieldError() throws Exception {
        fieldError(mvc.perform(caseType(post("/admin/case-types/{id}", 920_202L), "Taken Type")), "A case type with");
        entityManager.flush();
        assertThat(count("CASE_TYPE", "Other Type")).isEqualTo(1);
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void creatingATeamWithATakenNameIsAFieldError() throws Exception {
        fieldError(mvc.perform(team(post("/admin/teams"), "Taken Team")), "A team with this name");
        entityManager.flush();
        assertThat(count("TEAM", "Taken Team")).isEqualTo(1);
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void renamingATeamToATakenNameIsAFieldError() throws Exception {
        fieldError(mvc.perform(team(post("/admin/teams/{id}", 920_302L), "Taken Team")), "A team with this name");
        entityManager.flush();
        assertThat(count("TEAM", "Other Team")).isEqualTo(1);
    }

    private static ResultActions fieldError(ResultActions result, String message) throws Exception {
        return result.andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("id=\"name-error\"")))
                .andExpect(content().string(containsString("aria-describedby=\"name-error\"")))
                .andExpect(content().string(containsString("aria-invalid=\"true\"")))
                .andExpect(content().string(containsString(message)));
    }

    private int count(String table, String name) {
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE NAME = ?", Integer.class, name);
        return rows == null ? 0 : rows;
    }

    private static MockHttpServletRequestBuilder policy(MockHttpServletRequestBuilder request, String name) {
        return request.param("name", name)
                .param("anchor", "OPENED_AT")
                .param("targetDays", "10")
                .param("atRiskPct", "80")
                .param("overdueReminderDays", "7")
                .param("escalateTo", "OWNER")
                .with(csrf());
    }

    private static MockHttpServletRequestBuilder caseType(MockHttpServletRequestBuilder request, String name) {
        return request.param("name", name)
                .param("slaPolicyId", "920101")
                .param("retentionYears", "7")
                .param("active", "true")
                .param("outcomes", "Resolved")
                .with(csrf());
    }

    private static MockHttpServletRequestBuilder team(MockHttpServletRequestBuilder request, String name) {
        return request.param("name", name).param("active", "true").with(csrf());
    }

    private void user(long id, String name, GlobalRole role) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                id,
                "test-subject-" + id,
                name);
        jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, ?)", id, role.name());
    }
}
