// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cases list page (GET only, so no CSRF case): a Reader sees the team's standard case and not its restricted
 * one, in the rows, the count or the page; the owner sees both; a System Admin and another team's Manager get an
 * empty list, not an error; an unknown sort column and other values no form offers are 422; an htmx request gets
 * only the results fragment, focus marked on the header that asked; anonymous requests go to the identity provider.
 * Permissions come from seeded database rows.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CaseListControllerIT {

    static final long ANALYST = 980_001L;
    static final long READER = 980_002L;
    static final long OTHER_MANAGER = 980_003L;
    static final long SYSTEM_ADMIN = 980_004L;
    static final long TEAM = 980_100L;
    static final long OTHER_TEAM = 980_101L;

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
        user(ANALYST, "Synthetic List Analyst", null);
        user(READER, "Synthetic List Reader", null);
        user(OTHER_MANAGER, "Synthetic List Other Manager", null);
        user(SYSTEM_ADMIN, "Synthetic List System Admin", GlobalRole.SYSTEM_ADMIN);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic List Team')", TEAM);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic List Other Team')", OTHER_TEAM);
        CaseSeed seed = new CaseSeed(jdbc);
        seed.member(ANALYST, TEAM, TeamRole.ANALYST);
        seed.member(READER, TEAM, TeamRole.READER);
        seed.member(OTHER_MANAGER, OTHER_TEAM, TeamRole.MANAGER);
        CaseService.Actor analyst = new CaseService.Actor(
                new UserPermissions(ANALYST, false, Set.of(), Map.of(TEAM, TeamRole.ANALYST)), "Synthetic Analyst");
        standard = cases.create(analyst, fields(seed.caseType(), "Synthetic listed <b>case</b>", Sensitivity.STANDARD));
        restricted = cases.create(analyst, fields(seed.caseType(), "Synthetic hidden case", Sensitivity.RESTRICTED));
    }

    @Test
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void readersSeeStandardCasesAndNoTraceOfRestrictedOnes() throws Exception {
        String page = mvc.perform(get("/cases"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>Cases")))
                .andExpect(content().string(containsString("1 case found.")))
                .andExpect(content().string(containsString("Synthetic listed &lt;b&gt;case&lt;/b&gt;")))
                .andExpect(content().string(containsString("href=\"/cases/" + standard + "\"")))
                .andExpect(content().string(containsString("Synthetic List Team")))
                .andExpect(content().string(containsString("aria-sort=\"descending\"")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(page)
                .doesNotContain(restricted.toString(), "Synthetic hidden case", "Restricted", "Other Team")
                .contains("<caption id=\"tk-cases-caption\"", "<th scope=\"row\">", "scope=\"col\"");
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void theOwnerSeesBothAndTheRestrictedOneIsMarked() throws Exception {
        mvc.perform(get("/cases").param("owner", "me").param("q", "synthetic"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2 cases found.")))
                .andExpect(content().string(containsString(restricted.toString())))
                .andExpect(content().string(containsString("Restricted")))
                .andExpect(content().string(containsString("<option value=\"me\" selected=\"selected\">Me</option>")));
    }

    @Test
    @WithAppUser(id = OTHER_MANAGER, team = OTHER_TEAM, teamRole = TeamRole.MANAGER)
    void anotherTeamsManagerGetsAnEmptyList() throws Exception {
        mvc.perform(get("/cases"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No cases found.")))
                .andExpect(content().string(not(containsString("Synthetic listed"))))
                .andExpect(content().string(not(containsString("<table"))));
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void systemAdminsReadNoCaseContent() throws Exception {
        mvc.perform(get("/cases"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No cases found.")))
                .andExpect(content().string(not(containsString("Synthetic"))));
    }

    @Test
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void valuesNoFormOffersAreUnprocessable() throws Exception {
        for (String[] bad : new String[][] {
            {"sort", "subject"}, {"sort", "description"}, {"sort", "title desc"}, {"dir", "up"}, {"page", "-"},
            {"status", "GONE"}, {"type", "1 or 1=1"}, {"team", "x"}, {"owner", "someone"}, {"q", "x".repeat(201)}
        }) {
            mvc.perform(get("/cases").param(bad[0], bad[1]))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(content().string(not(containsString("Synthetic listed"))));
        }
        mvc.perform(get("/cases").param("sort", "subject").header("HX-Request", "true"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("tk-problem")));
    }

    @Test
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void htmxRequestsGetTheResultsFragmentWithFocusOnTheHeaderThatAsked() throws Exception {
        String fragment = mvc.perform(get("/cases")
                        .param("sort", "title")
                        .param("dir", "asc")
                        .header("HX-Request", "true")
                        .header("HX-Trigger", "tk-sort-title"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(fragment)
                .doesNotContain("<html", "<h1", "tk-case-filter")
                .contains("aria-sort=\"ascending\"", "data-tk-announce=\"1 case found.\"")
                .containsPattern("id=\"tk-sort-title\"[^>]*data-tk-focus=\"true\"")
                .containsPattern("href=\"/cases\\?sort=title&amp;dir=desc\"");
        assertThat(fragment.indexOf("data-tk-focus")).isEqualTo(fragment.lastIndexOf("data-tk-focus"));

        String hostile = mvc.perform(get("/cases").header("HX-Request", "true").header("HX-Trigger", "x\" onclick=\""))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(hostile).doesNotContain("onclick").containsPattern("id=\"tk-results-status\"[^>]*data-tk-focus");
    }

    @Test
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void theFilterFormOffersOnlyReadableTeamsAndOwnersAndKeepsItsStateInTheLinks() throws Exception {
        String page = mvc.perform(
                        get("/cases").param("team", String.valueOf(TEAM)).param("status", "OPEN"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(page)
                .contains("role=\"search\"", "<label class=\"form-label\" for=\"tk-q\">Search</label>")
                .contains("<option value=\"" + TEAM + "\" selected=\"selected\">Synthetic List Team</option>")
                .contains("<option value=\"" + ANALYST + "\">Synthetic List Analyst</option>")
                .contains("<option value=\"OPEN\" selected=\"selected\">Open</option>")
                .doesNotContain("Synthetic List Other Team", "Synthetic List Other Manager");

        String fragment = mvc.perform(get("/cases")
                        .param("q", "listed 50%")
                        .header("HX-Request", "true")
                        .header("HX-Trigger", "tk-case-filter"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(fragment)
                .contains("data-tk-announce=\"No cases found.\"")
                .containsPattern("id=\"tk-results-status\"[^>]*data-tk-focus=\"true\"");
        String found = mvc.perform(get("/cases").param("q", "LISTED").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(found)
                .contains("data-tk-announce=\"1 case found.\"", standard.toString())
                .contains("href=\"/cases?q=LISTED&amp;sort=title&amp;dir=asc\"")
                .doesNotContain(restricted.toString());
    }

    @Test
    void anonymousRequestsGoToTheIdentityProvider() throws Exception {
        mvc.perform(get("/cases")).andExpect(status().isFound());
    }

    private static CaseFields fields(long caseType, String title, Sensitivity sensitivity) {
        return new CaseFields(
                caseType,
                title,
                CasePriority.NORMAL,
                LocalDate.now(ZoneOffset.UTC),
                TEAM,
                ANALYST,
                null,
                "Synthetic description",
                sensitivity);
    }

    private void user(long id, String name, @Nullable GlobalRole role) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                id,
                "case-list-subject-" + id,
                name);
        if (role != null) {
            jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, ?)", id, role.name());
        }
    }
}
