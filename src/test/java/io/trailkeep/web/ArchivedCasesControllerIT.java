// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.casefile.CaseFields;
import io.trailkeep.casefile.CasePriority;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.GlobalRole;
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
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.transaction.annotation.Transactional;

/**
 * Archived cases over HTTP (WP-24 part a, BUILD_PLAN §4.6): the cases list, its search and its export leave them out
 * by default; a Manager is offered "Archived" in the status filter and lists them with it, while an Analyst is not
 * offered it and gets 422 for asking; an archived case still opens by its link but is read-only: no edit, comment or
 * reopen, each refused with 403.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ArchivedCasesControllerIT {

    private static final long ANALYST = 985_601L;
    private static final long MANAGER = 985_602L;
    private static final long TEAM = 985_611L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CaseService cases;

    @Autowired
    private EntityManager entityManager;

    private CaseNumber open;
    private CaseNumber archived;

    @BeforeEach
    void seed() {
        user(ANALYST, "Synthetic Archive Analyst");
        user(MANAGER, "Synthetic Archive Manager");
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Archive Team')", TEAM);
        CaseSeed seed = new CaseSeed(jdbc);
        seed.member(ANALYST, TEAM, TeamRole.ANALYST);
        seed.member(MANAGER, TEAM, TeamRole.MANAGER);
        CaseService.Actor analyst = new CaseService.Actor(
                new UserPermissions(ANALYST, false, Set.of(), Map.of(TEAM, TeamRole.ANALYST)), "Synthetic Analyst");
        open = cases.create(analyst, fields(seed.caseType(), "Synthetic retained open case"));
        archived = cases.create(analyst, fields(seed.caseType(), "Synthetic retained archived case"));
        entityManager.flush();
        jdbc.update(
                "UPDATE CASE_RECORD SET STATUS = 'ARCHIVED', CLOSED_AT = CURRENT_TIMESTAMP,"
                        + " ARCHIVED_AT = CURRENT_TIMESTAMP WHERE CASE_NUMBER = ?",
                archived.toString());
        entityManager.clear();
    }

    @Test
    @WithAppUser(id = MANAGER, team = TEAM, teamRole = TeamRole.MANAGER)
    void managersAreOfferedArchivedCasesWhichTheDefaultListAndSearchLeaveOut() throws Exception {
        String list = page("/cases", status().isOk());
        assertThat(list).contains("/cases/" + open, "id=\"tk-status-ARCHIVED\"").doesNotContain("/cases/" + archived);
        assertThat(page("/cases?q=retained", status().isOk()))
                .contains("/cases/" + open)
                .doesNotContain("/cases/" + archived);
        assertThat(page("/cases?status=ARCHIVED&q=retained", status().isOk()))
                .contains("/cases/" + archived)
                .doesNotContain("/cases/" + open);
        assertThat(page("/cases/export.csv?status=ARCHIVED", status().isOk())).contains(archived.toString());
        assertThat(page("/cases/export.csv", status().isOk())).doesNotContain(archived.toString());
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void analystsAreNotOfferedArchivedCasesAndAskingIsUnprocessable() throws Exception {
        assertThat(page("/cases", status().isOk()))
                .contains("id=\"tk-status-CLOSED\"")
                .doesNotContain("tk-status-ARCHIVED", "/cases/" + archived);
        mvc.perform(get("/cases").param("status", "ARCHIVED")).andExpect(status().isUnprocessableContent());
        mvc.perform(get("/cases/export.csv").param("status", "ARCHIVED")).andExpect(status().isUnprocessableContent());
    }

    @Test
    @WithAppUser(id = MANAGER, team = TEAM, teamRole = TeamRole.MANAGER)
    void archivedCaseOpensByItsLinkButIsReadOnly() throws Exception {
        String casePage = page("/cases/" + archived, status().isOk());
        assertThat(casePage)
                .contains("Synthetic retained archived case", "Archived")
                .doesNotContain("/cases/" + archived + "/edit", "/cases/" + archived + "/reopen");
        String url = "/cases/" + archived;
        mvc.perform(get(url + "/edit")).andExpect(status().isForbidden());
        mvc.perform(get(url + "/reopen")).andExpect(status().isForbidden());
        mvc.perform(post(url + "/reopen").param("reason", "A synthetic reason").with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post(url + "/comments").param("body", "A synthetic comment").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAppUser(id = ANALYST, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void complianceAdminsAreOfferedArchivedCases() throws Exception {
        jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, 'COMPLIANCE_ADMIN')", ANALYST);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE USER_ID = ?", ANALYST);
        assertThat(page("/cases?status=ARCHIVED", status().isOk()))
                .contains("tk-status-ARCHIVED", "/cases/" + archived);
    }

    private String page(String path, ResultMatcher expected) throws Exception {
        return mvc.perform(get(path))
                .andExpect(expected)
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll(">\\s+<", "><");
    }

    private static CaseFields fields(long caseType, String title) {
        return new CaseFields(
                caseType,
                title,
                CasePriority.NORMAL,
                LocalDate.now(ZoneOffset.UTC),
                TEAM,
                ANALYST,
                null,
                "Synthetic description",
                Sensitivity.STANDARD);
    }

    private void user(long id, String name) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                id,
                "archive-subject-" + id,
                name);
    }
}
