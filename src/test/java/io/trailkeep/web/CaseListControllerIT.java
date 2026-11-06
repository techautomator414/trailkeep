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
import java.util.regex.Pattern;
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
    static final long COMPLIANCE_ADMIN = 980_005L;
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
        user(COMPLIANCE_ADMIN, "Synthetic List Compliance Admin", GlobalRole.COMPLIANCE_ADMIN);
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
            {"sort", "subject"},
            {"sort", "description"},
            {"sort", "title desc"},
            {"dir", "up"},
            {"page", "-"},
            {"status", "GONE"},
            {"type", "1 or 1=1"},
            {"team", "x"},
            {"owner", "someone"},
            {"q", "x".repeat(201)},
            {"sla", "LATE"},
            {"priority", "URGENT"},
            {"sensitivity", "SECRET"},
            {"from", "1 Oct 2026"},
            {"to", "2026-13-01"}
        }) {
            mvc.perform(get("/cases").param(bad[0], bad[1]))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(content().string(not(containsString("Synthetic listed"))));
        }
        mvc.perform(get("/cases").param("from", "2026-10-02").param("to", "2026-10-01"))
                .andExpect(status().isUnprocessableContent());
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
                .containsPattern("id=\"tk-status-OPEN\"\\s+value=\"OPEN\"[^>]*checked=\"checked\"")
                .containsPattern("id=\"tk-status-CLOSED\"\\s+value=\"CLOSED\">")
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
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void theFiltersAreALabelledDialogOpenedFromALinkThatWorksWithoutJavaScript() throws Exception {
        String page = html(get("/cases").param("status", "OPEN"));
        assertThat(page)
                .containsPattern("<a id=\"tk-filters-open\" [^>]*aria-haspopup=\"dialog\"[^>]*"
                        + "data-tk-dialog-open=\"tk-filter-dialog\"[^>]*href=\"/cases\\?status=OPEN&amp;filters=1\">"
                        + "Filters</a>")
                .contains("<dialog id=\"tk-filter-dialog\" class=\"tk-dialog tk-filter-dialog\""
                        + " aria-labelledby=\"tk-filter-title\">")
                .contains("<h2 id=\"tk-filter-title\">Filter cases</h2>")
                .containsPattern("<fieldset [^>]*aria-describedby=\"tk-status-hint\">\\s*<legend[^>]*>Status</legend>")
                .contains("<label class=\"form-check-label\" for=\"tk-status-PENDING_CLOSURE\">Pending closure</label>")
                .containsPattern("<legend[^>]*>SLA state</legend>")
                .contains("<label class=\"form-check-label\" for=\"tk-sla-OVERDUE\">Overdue</label>")
                .containsPattern("id=\"tk-status-OPEN\"[^>]*data-tk-dialog-focus=\"true\"")
                .contains("<label class=\"form-label\" for=\"tk-priority\">Priority</label>")
                .contains("<label class=\"form-label\" for=\"tk-from\">Identified from</label>")
                .contains("<input id=\"tk-from\" class=\"form-control\" type=\"date\" name=\"from\"")
                .contains("<label class=\"form-label\" for=\"tk-to\">Identified to</label>")
                .contains("up to ten words; every word must appear.")
                .containsPattern(
                        "<a class=\"btn btn-outline-secondary\" data-tk-dialog-close href=\"/cases\\?status=OPEN\">")
                // A Reader reads no one's restricted cases: no sensitivity filter.
                .doesNotContain("tk-sensitivity", "Restricted");
        assertThat(page.indexOf("data-tk-dialog-focus")).isEqualTo(page.lastIndexOf("data-tk-dialog-focus"));
        assertThat(page).doesNotContainPattern("<dialog [^>]*open=");

        mvc.perform(get("/cases").param("filters", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("aria-labelledby=\"tk-filter-title\" open=\"open\">")))
                .andExpect(content().string(containsString("href=\"/cases?filters=1\"")));
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void sensitivityIsOfferedToThoseWhoReadOthersRestrictedCases() throws Exception {
        mvc.perform(get("/cases").param("sensitivity", "restricted"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<label class=\"form-label\" for=\"tk-sensitivity\">")))
                .andExpect(content()
                        .string(containsString(
                                "<option value=\"RESTRICTED\" selected=\"selected\">" + "Restricted</option>")))
                .andExpect(content().string(containsString("1 case found.")))
                .andExpect(content().string(containsString(restricted.toString())))
                .andExpect(content().string(not(containsString(standard.toString()))));
    }

    @Test
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void activeFiltersAreChipsThatLinkToTheListWithoutThem() throws Exception {
        String page = html(get("/cases")
                .param("q", "listed")
                .param("status", "OPEN", "CLOSED")
                .param("sla", "ON_TRACK")
                .param("priority", "NORMAL")
                .param("from", "2026-01-01")
                .param("team", String.valueOf(TEAM))
                .param("owner", "me")
                .param("type", "424242")
                .param("sort", "title"));
        String rest = "&amp;sort=title&amp;dir=asc";
        assertThat(page)
                .contains("<ul class=\"tk-chips\" aria-label=\"Active filters\">")
                .containsPattern(chip(
                        "Search",
                        "listed",
                        "status=OPEN&amp;status=CLOSED&amp;sla=ON_TRACK&amp;priority=NORMAL"
                                + "&amp;from=2026-01-01&amp;type=424242&amp;team=" + TEAM + "&amp;owner=me" + rest))
                .containsPattern(chip(
                        "Status",
                        "Open",
                        "q=listed&amp;status=CLOSED&amp;sla=ON_TRACK&amp;priority=NORMAL"
                                + "&amp;from=2026-01-01&amp;type=424242&amp;team=" + TEAM + "&amp;owner=me" + rest))
                .containsPattern(chip(
                        "Status",
                        "Closed",
                        "q=listed&amp;status=OPEN&amp;sla=ON_TRACK&amp;priority=NORMAL"
                                + "&amp;from=2026-01-01&amp;type=424242&amp;team=" + TEAM + "&amp;owner=me" + rest))
                .contains("aria-label=\"Remove filter: SLA state On track\"")
                .contains("aria-label=\"Remove filter: Priority Normal\"")
                .contains("aria-label=\"Remove filter: Identified from 2026-01-01\"")
                // An id no choice offers shows as the id; a readable team by its name; "me" as Me.
                .contains("aria-label=\"Remove filter: Case type 424242\"")
                .contains("aria-label=\"Remove filter: Team Synthetic List Team\"")
                .contains("aria-label=\"Remove filter: Owner Me\"")
                .contains("<span>Team: Synthetic List Team</span>");
        assertThat(page.indexOf("tk-chips")).isLessThan(page.indexOf("id=\"tk-results-status\""));
    }

    @Test
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void htmxResponsesCarryTheChipsAndRefreshTheFilterControlsOutOfBand() throws Exception {
        String fragment = html(get("/cases")
                .param("status", "CLOSED")
                .header("HX-Request", "true")
                .header("HX-Trigger", "tk-case-filter"));
        assertThat(fragment)
                .containsPattern(chip("Status", "Closed", ""))
                .contains("<div id=\"tk-search-field\" class=\"col-12 col-lg-6\" hx-swap-oob=\"true\">")
                .contains("<div id=\"tk-filter-fields\" class=\"row g-3\" hx-swap-oob=\"true\">")
                .containsPattern("id=\"tk-status-CLOSED\"\\s+value=\"CLOSED\"[^>]*checked=\"checked\"")
                .doesNotContain("<dialog", "<h1", "<h2")
                .containsPattern("id=\"tk-results-status\"[^>]*data-tk-focus=\"true\"");
        assertThat(html(get("/cases").header("HX-Request", "true"))).doesNotContain("tk-chips");
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /** A pattern for a chip's link: plain href and hx-get to the same URL, its accessible name and its text. */
    private static String chip(String what, String value, String without) {
        String url = without.isEmpty() ? "/cases" : "/cases?" + without;
        return Pattern.quote("<a class=\"tk-chip tk-chip-remove\" href=\"" + url + "\"")
                + "[^>]*"
                + Pattern.quote(" aria-label=\"Remove filter: " + what + " " + value + "\" hx-get=\"" + url
                        + "\"><span>" + what + ": " + value + "</span>");
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
