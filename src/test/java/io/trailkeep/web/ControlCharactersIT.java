// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Text with a control character (issue 212) at the four entry points the ZAP scan hit, a multipart text part, and the
 * htmx form of the answer: each is a 400, never a 500, and nothing is written. Ordinary multi-line text, a tab and
 * non-ASCII text still go through. The sweep over every mapped route is {@code ControlCharacterSweepIT}.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ControlCharactersIT {

    static final long PERSON = 970_001L;
    static final long TEAM = 970_100L;

    /** U+0000 inside otherwise ordinary text. */
    private static final String NUL = "before" + (char) 0 + "after";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private CaseNumber number;

    @BeforeEach
    void seed() {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, 'control-subject', 'Synthetic Person', 'user@example.invalid', FALSE,"
                        + " CURRENT_TIMESTAMP)",
                PERSON);
        jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, 'COMPLIANCE_ADMIN')", PERSON);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Control Team')", TEAM);
        CaseSeed seed = new CaseSeed(jdbc);
        seed.member(PERSON, TEAM, TeamRole.ANALYST);
        number = seed.caseRow(TEAM, PERSON, Sensitivity.STANDARD, CaseStatus.OPEN);
        entityManager.flush();
    }

    @Test
    @WithAppUser(id = PERSON, globalRole = GlobalRole.COMPLIANCE_ADMIN, team = TEAM, teamRole = TeamRole.ANALYST)
    void caseListFilterParametersAreRefused() throws Exception {
        for (String filter : new String[] {"q", "owner", "sort", "dir", "page", "from", "to", "type", "team"}) {
            mvc.perform(get("/cases").param(filter, NUL)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/cases").param("q", "ordinary text")).andExpect(status().isOk());
    }

    @Test
    @WithAppUser(id = PERSON, globalRole = GlobalRole.COMPLIANCE_ADMIN, team = TEAM, teamRole = TeamRole.ANALYST)
    void slaPolicyAndCaseTypeFormsAreRefusedAndWriteNothing() throws Exception {
        mvc.perform(post("/admin/sla-policies")
                        .param("name", NUL)
                        .param("anchor", "OPENED_AT")
                        .param("targetDays", "10")
                        .param("atRiskPct", "80")
                        .param("overdueReminderDays", "7")
                        .param("escalateTo", "OWNER")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/case-types")
                        .param("name", NUL)
                        .param("slaPolicyId", "1")
                        .param("retentionYears", "7")
                        .param("active", "true")
                        .param("outcomes", "Resolved")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SLA_POLICY WHERE NAME LIKE 'before%'", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM CASE_TYPE WHERE NAME LIKE 'before%'", Long.class))
                .isZero();
    }

    @Test
    @WithAppUser(id = PERSON, globalRole = GlobalRole.COMPLIANCE_ADMIN, team = TEAM, teamRole = TeamRole.ANALYST)
    void commentWithAControlCharacterIsRefusedAndNothingIsPosted() throws Exception {
        mvc.perform(comment(NUL)).andExpect(status().isBadRequest());
        entityManager.flush();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM CASE_COMMENT WHERE CASE_ID = ?",
                        Long.class,
                        new CaseSeed(jdbc).caseId(number)))
                .isZero();
    }

    @Test
    @WithAppUser(id = PERSON, globalRole = GlobalRole.COMPLIANCE_ADMIN, team = TEAM, teamRole = TeamRole.ANALYST)
    void multipartTextPartAndAParameterNameAreCheckedToo() throws Exception {
        mvc.perform(multipart("/cases/{number}/attachments", number.toString())
                        .param("note", NUL)
                        .with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/cases/{number}/comments", number.toString())
                        .param("body", "fine")
                        .param("na" + (char) 0 + "me", "x")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAppUser(id = PERSON, globalRole = GlobalRole.COMPLIANCE_ADMIN, team = TEAM, teamRole = TeamRole.ANALYST)
    void anHtmxRequestGetsTheProblemFragmentWithoutTheTextSent() throws Exception {
        mvc.perform(comment(NUL).header("HX-Request", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("role=\"alert\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("before"))));
    }

    @Test
    @WithAppUser(id = PERSON, globalRole = GlobalRole.COMPLIANCE_ADMIN, team = TEAM, teamRole = TeamRole.ANALYST)
    void multiLineTabbedAndNonAsciiTextIsStillAccepted() throws Exception {
        mvc.perform(comment("line one\r\n\tline two: é ü ß €")).andExpect(status().isFound());
    }

    private MockHttpServletRequestBuilder comment(String body) {
        return post("/cases/{number}/comments", number.toString())
                .param("body", body)
                .with(csrf());
    }
}
