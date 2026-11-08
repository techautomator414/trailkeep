// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
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
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Legal hold over HTTP (WP-24 part b). A Compliance Admin's case page offers "Place legal hold" (a dialog opener that
 * is also a link to the same form as a page); placing it with a reason returns to the case with a notice, the "Legal
 * hold" chip and who, when and why, and the action becomes "Lift legal hold"; an archived case can be held too. A
 * Manager sees the chip but not the reason or the action, and is refused the form and the post (403). A missing reason
 * is 422 with the field marked and summarized; a stale form (the hold changed meanwhile) is 409 with Reload; an
 * unknown case is 404; CSRF is required; anonymous requests go to the identity provider.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LegalHoldControllerIT {

    private static final long ANALYST = 985_701L;
    private static final long MANAGER = 985_702L;
    private static final long ADMIN = 985_703L;
    private static final long TEAM = 985_711L;
    private static final String REASON = "Synthetic litigation hold";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CaseService cases;

    @Autowired
    private EntityManager entityManager;

    private CaseNumber number;
    private String hold = "";

    @BeforeEach
    void seed() {
        user(ANALYST);
        user(MANAGER);
        user(ADMIN);
        jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, 'COMPLIANCE_ADMIN')", ADMIN);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Hold Team')", TEAM);
        CaseSeed seed = new CaseSeed(jdbc);
        seed.member(ANALYST, TEAM, TeamRole.ANALYST);
        seed.member(MANAGER, TEAM, TeamRole.MANAGER);
        number = cases.create(
                new CaseService.Actor(
                        new UserPermissions(ANALYST, false, Set.of(), Map.of(TEAM, TeamRole.ANALYST)), "Analyst"),
                new CaseFields(
                        seed.caseType(),
                        "Synthetic held case",
                        CasePriority.NORMAL,
                        LocalDate.now(ZoneOffset.UTC),
                        TEAM,
                        ANALYST,
                        null,
                        "Synthetic description",
                        Sensitivity.STANDARD));
        hold = "/cases/" + number + "/legal-hold";
    }

    @Test
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void complianceAdminsPlaceAHoldFromTheCasePageAndThenLiftIt() throws Exception {
        assertThat(page(get("/cases/" + number), status().isOk()))
                .contains("href=\"" + hold + "\"", "aria-haspopup=\"dialog\">Place legal hold</a>")
                .contains("<input type=\"hidden\" name=\"hold\" value=\"true\">")
                .doesNotContain("tk-hold-title");
        assertThat(page(get(hold), status().isOk()))
                .contains("<h1>Place a legal hold on " + number + "</h1>")
                .contains("<label class=\"form-label\" for=\"tk-hold-reason\">Reason (required)</label>");

        Cookie session = Objects.requireNonNull(mvc.perform(place(true, REASON).with(csrf()))
                .andExpect(redirectedUrl("/cases/" + number))
                .andReturn()
                .getResponse()
                .getCookie("SESSION"));
        flushAndClear();
        assertThat(page(get("/cases/" + number).cookie(session), status().isOk()))
                .contains("The case is on legal hold. It will be neither archived nor purged")
                .contains("<span aria-hidden=\"true\">&#x2696;</span><span>Legal hold</span>")
                .contains("<h2 id=\"tk-hold-title\" class=\"h5\">Legal hold</h2>", REASON)
                .contains(">Lift legal hold</a>", "<input type=\"hidden\" name=\"hold\" value=\"false\">");

        mvc.perform(place(false, "Synthetic matter settled").with(csrf())).andExpect(redirectedUrl("/cases/" + number));
        flushAndClear();
        assertThat(page(get("/cases/" + number), status().isOk()))
                .doesNotContain("tk-hold-title", "&#x2696;")
                .contains("<h2 id=\"tk-hold-history-title\" class=\"h5\">Legal hold history</h2>")
                .contains("<li class=\"mb-2\"><span>Lifted by Synthetic Hold User " + ADMIN + " on ")
                .contains("</span>: <span class=\"tk-description\">Synthetic matter settled</span></li>")
                .contains("<span>Placed by Synthetic Hold User " + ADMIN + " on ", REASON);
    }

    @Test
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void anArchivedCaseCanBeHeld() throws Exception {
        archive();
        mvc.perform(place(true, REASON).with(csrf())).andExpect(redirectedUrl("/cases/" + number));
    }

    @Test
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void missingReasonsAreUnprocessableAndStaleFormsConflicts() throws Exception {
        assertThat(page(place(true, " ").with(csrf()), status().isUnprocessableContent()))
                .contains("<h2 id=\"tk-hold-errors\" class=\"h5\">The legal hold was not changed.</h2>")
                .contains("aria-invalid=\"true\"", "id=\"tk-hold-reason-error\"");
        mvc.perform(place(true, REASON).with(csrf())).andExpect(status().isFound());
        flushAndClear();
        assertThat(page(place(true, "Another reason").with(csrf()), status().isConflict()))
                .contains("<h2 id=\"tk-hold-conflict\" class=\"h5\">Someone changed this case’s legal hold meanwhile.")
                .contains("<a class=\"btn btn-primary\" href=\"/cases/" + number + "\">Reload</a>")
                .doesNotContain("name=\"reason\"");
        mvc.perform(place(true, REASON)).andExpect(status().isForbidden());
        mvc.perform(get("/cases/SEED-2026-999999999/legal-hold")).andExpect(status().isNotFound());
    }

    @Test
    @WithAppUser(id = MANAGER, team = TEAM, teamRole = TeamRole.MANAGER)
    void managersSeeTheChipButNeitherTheReasonNorTheAction() throws Exception {
        entityManager.flush();
        jdbc.update(
                "UPDATE CASE_RECORD SET LEGAL_HOLD = TRUE, LEGAL_HOLD_BY = ?, LEGAL_HOLD_AT = CURRENT_TIMESTAMP,"
                        + " LEGAL_HOLD_REASON = ? WHERE CASE_NUMBER = ?",
                ADMIN,
                REASON,
                number.toString());
        flushAndClear();
        assertThat(page(get("/cases/" + number), status().isOk()))
                .contains("<span>Legal hold</span>")
                .doesNotContain(REASON, "tk-hold-title", "tk-hold-history-title", hold);
        mvc.perform(get(hold)).andExpect(status().isForbidden());
        mvc.perform(place(false, "Synthetic reason").with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void anonymousRequestsGoToTheIdentityProvider() throws Exception {
        mvc.perform(get(hold)).andExpect(status().isFound());
    }

    private MockHttpServletRequestBuilder place(boolean placing, String reason) {
        return post(hold).param("hold", Boolean.toString(placing)).param("reason", reason);
    }

    private void archive() {
        entityManager.flush();
        jdbc.update(
                "UPDATE CASE_RECORD SET STATUS = 'ARCHIVED', CLOSED_AT = CURRENT_TIMESTAMP,"
                        + " ARCHIVED_AT = CURRENT_TIMESTAMP WHERE CASE_NUMBER = ?",
                number.toString());
        entityManager.clear();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private String page(MockHttpServletRequestBuilder request, ResultMatcher expected) throws Exception {
        return mvc.perform(request)
                .andExpect(expected)
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll(">\\s+<", "><");
    }

    private void user(long id) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                id,
                "hold-subject-" + id,
                "Synthetic Hold User " + id);
    }
}
