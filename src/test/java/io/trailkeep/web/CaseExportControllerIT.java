// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.casefile.CaseExportService;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /cases/export.csv} (WP-15 part 4a): a Reader's export is a UTF-8 CSV attachment with a byte order mark,
 * never cached or sniffed, without the restricted case and with details redacted even when {@code details=true} is
 * forced; a Compliance Admin is offered "Include details" and gets them; a filter over {@code app.export.max-rows}
 * (here 1) is 422 with the reason on the cases page; values no form offers are 422; anonymous requests go to the
 * identity provider.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.export.max-rows=1")
@Transactional
class CaseExportControllerIT {

    static final long ANALYST = 982_001L;
    static final long READER = 982_002L;
    static final long ADMIN = 982_003L;
    static final long TEAM = 982_100L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CaseService cases;

    private CaseNumber standard;
    private CaseNumber restricted;

    @BeforeEach
    void seed() {
        user(ANALYST, "Synthetic Export Analyst", null);
        user(READER, "Synthetic Export Reader", null);
        user(ADMIN, "Synthetic Export Admin", GlobalRole.COMPLIANCE_ADMIN);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Export Team')", TEAM);
        CaseSeed seed = new CaseSeed(jdbc);
        seed.member(ANALYST, TEAM, TeamRole.ANALYST);
        seed.member(READER, TEAM, TeamRole.READER);
        CaseService.Actor analyst = new CaseService.Actor(
                new UserPermissions(ANALYST, false, Set.of(), Map.of(TEAM, TeamRole.ANALYST)), "Synthetic Analyst");
        standard = cases.create(analyst, fields(seed.caseType(), "=HYPERLINK(\"x\") standard", Sensitivity.STANDARD));
        restricted = cases.create(analyst, fields(seed.caseType(), "Synthetic hidden export", Sensitivity.RESTRICTED));
    }

    @Test
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void readersExportAnAttachmentWithoutRestrictedRowsAndWithDetailsRedactedEvenWhenForced() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/cases/export.csv").param("details", "true"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);

        assertThat(response.getContentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(response.getHeader("Content-Disposition"))
                .matches("attachment; filename=\"cases-\\d{8}-\\d{4}\\.csv\"");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(body)
                .startsWith("\uFEFF\"Case number\",\"Title\",")
                .contains("\"" + standard + "\",\"'=HYPERLINK(\"\"x\"\") standard\"")
                .contains("\"" + CaseExportService.REDACTED + "\",\"" + CaseExportService.REDACTED + "\"\r\n")
                .doesNotContain(
                        restricted.toString(), "Synthetic hidden export", "Synthetic description", "Synthetic subject");
        assertThat(body.split("\r\n", -1)).hasSize(3);
    }

    @Test
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void theCasesPageOffersExportWithTheListsQueryAndNoDetailsForAReader() throws Exception {
        String page = mvc.perform(get("/cases").param("status", "OPEN").param("sort", "title"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(page)
                .contains("<form id=\"tk-export\" class=\"mt-3\" method=\"get\" action=\"/cases/export.csv\">")
                .contains("<input type=\"hidden\" name=\"status\" value=\"OPEN\">")
                .contains("<input type=\"hidden\" name=\"sort\" value=\"title\">")
                .contains("<input type=\"hidden\" name=\"dir\" value=\"asc\">")
                .containsPattern("id=\"tk-export-csv\"[^>]*aria-describedby=\"tk-export-hint\">Export CSV</button>")
                .contains("Exports all 1 matching cases, every page, in this order.")
                .doesNotContain("tk-export-details");
    }

    @Test
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void complianceAdminsAreOfferedDetailsAndGetThem() throws Exception {
        assertThat(mvc.perform(get("/cases").param("q", "standard"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains(
                        "<input class=\"form-check-input\" type=\"checkbox\" id=\"tk-export-details\" name=\"details\""
                                + " value=\"true\">")
                .contains("<label class=\"form-check-label\" for=\"tk-export-details\">");

        String body = mvc.perform(
                        get("/cases/export.csv").param("q", "standard").param("details", "true"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .contains("\"Synthetic subject\",\"Synthetic description\"\r\n")
                .doesNotContain(CaseExportService.REDACTED);
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void filtersMatchingMoreThanTheCapAreUnprocessableWithTheReasonOnTheCasesPage() throws Exception {
        String page = mvc.perform(get("/cases/export.csv").param("status", "OPEN"))
                .andExpect(status().isUnprocessableContent())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(page)
                .contains("<h1>Cases</h1>")
                .containsPattern("role=\"alert\" tabindex=\"-1\" data-tk-error-summary>\\s*<p id=\"tk-export-error\""
                        + " class=\"mb-0\">Not exported: 2 cases match, more than the 1 one export may hold.")
                .contains("aria-describedby=\"tk-export-error tk-export-hint\"");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM AUDIT_EVENT WHERE ACTION = 'CASES_EXPORTED' AND ACTOR_ID = ?",
                        Long.class,
                        ANALYST))
                .isZero();
        mvc.perform(get("/cases/export.csv").param("q", "standard")).andExpect(status().isOk());
    }

    @Test
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void valuesNoFormOffersAreUnprocessable() throws Exception {
        for (String[] bad : new String[][] {{"sort", "subject"}, {"status", "GONE"}, {"from", "yesterday"}}) {
            mvc.perform(get("/cases/export.csv").param(bad[0], bad[1])).andExpect(status().isUnprocessableContent());
        }
    }

    @Test
    void anonymousRequestsGoToTheIdentityProvider() throws Exception {
        mvc.perform(get("/cases/export.csv")).andExpect(status().isFound());
    }

    private static CaseFields fields(long caseType, String title, Sensitivity sensitivity) {
        return new CaseFields(
                caseType,
                title,
                CasePriority.NORMAL,
                LocalDate.now(ZoneOffset.UTC),
                TEAM,
                ANALYST,
                "Synthetic subject",
                "Synthetic description",
                sensitivity);
    }

    private void user(long id, String name, @Nullable GlobalRole role) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                id,
                "case-export-subject-" + id,
                name);
        if (role != null) {
            jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, ?)", id, role.name());
        }
    }
}
