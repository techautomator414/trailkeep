// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.casefile.CaseListQuery;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.SavedViewService;
import io.trailkeep.casefile.SavedViewService.Created;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.UserPermissions;
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saved views over HTTP (WP-15 part 3): "Save view" on the cases page, the POST that saves (CSRF required, 422 with
 * the dialog open and the reason for a refused name, a redirect to the view's list otherwise), the views under
 * "Cases" in the navigation for their owner only, and the manage page whose Delete answers 404 for another person's
 * view or an unknown id. Anonymous requests go to the identity provider.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SavedViewControllerIT {

    static final long OWNER = 981_001L;
    static final long OTHER = 981_002L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SavedViewService views;

    private long othersView;

    @BeforeEach
    void seed() {
        user(OWNER, "Synthetic View Owner");
        user(OTHER, "Synthetic View Other");
        othersView = ((Created) views.save(
                        new Actor(new UserPermissions(OTHER, false, Set.of(), Map.of()), "Synthetic View Other"),
                        "Their private view",
                        CaseListQuery.parse(Map.of("status", List.of("CLOSED")))))
                .view()
                .id();
    }

    @Test
    @WithAppUser(id = OWNER)
    void theCasesPageOffersSaveViewForAFilteredListAsADialogThatWorksWithoutJavaScript() throws Exception {
        assertThat(html(get("/cases"))).doesNotContain("id=\"tk-save-open\"");

        String page = html(get("/cases").param("status", "OPEN").param("sort", "title"));
        assertThat(page)
                .containsPattern("<a id=\"tk-save-open\" [^>]*aria-haspopup=\"dialog\"[^>]*"
                        + "data-tk-dialog-open=\"tk-save-dialog\"[^>]*"
                        + "href=\"/cases\\?status=OPEN&amp;sort=title&amp;dir=asc&amp;save=1\">Save view</a>")
                .contains("<dialog id=\"tk-save-dialog\" class=\"tk-dialog\" aria-labelledby=\"tk-save-title\">")
                .contains("<form method=\"post\" action=\"/cases/views\">")
                .contains("<input type=\"hidden\" name=\"query\" value=\"status=OPEN&amp;sort=title&amp;dir=asc\">")
                .contains("<label class=\"form-label\" for=\"tk-view-name\">Name (required)</label>")
                .containsPattern("id=\"tk-view-name\"[^>]*data-tk-dialog-focus=\"true\"[^>]*"
                        + "aria-describedby=\"tk-view-hint\"")
                .containsPattern("name=\"_csrf\"");
        assertThat(html(get("/cases").param("status", "OPEN").param("save", "1")))
                .contains("aria-labelledby=\"tk-save-title\" open=\"open\">");
    }

    @Test
    @WithAppUser(id = OWNER)
    void savingRedirectsToTheViewWhichThenAppearsInTheNavigationOfItsOwnerOnly() throws Exception {
        mvc.perform(post("/cases/views")
                        .param("name", "  Open by title ")
                        .param("query", "sort=title&dir=asc&status=open&page=4")
                        .with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/cases?status=OPEN&sort=title&dir=asc"));

        assertThat(html(get("/cases")))
                .contains("<summary>Saved views</summary>")
                .contains("<a href=\"/cases?status=OPEN&amp;sort=title&amp;dir=asc\">Open by title</a>")
                .contains("<a href=\"/cases/views\">Manage views</a>")
                .doesNotContain("Their private view");
    }

    @Test
    @WithAppUser(id = OWNER)
    void refusedNamesAreUnprocessableWithTheDialogOpenAndTheReasonTiedToTheField() throws Exception {
        String blank = mvc.perform(post("/cases/views")
                        .param("name", "  ")
                        .param("query", "status=OPEN")
                        .with(csrf()))
                .andExpect(status().isUnprocessableContent())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(blank)
                .contains("aria-labelledby=\"tk-save-title\" open=\"open\">")
                .containsPattern("role=\"alert\" tabindex=\"-1\" data-tk-error-summary>\\s*"
                        + "<p id=\"tk-view-error\" class=\"mb-0\">Not saved: give the view a name.</p>")
                .containsPattern("id=\"tk-view-name\"[^>]*aria-invalid=\"true\"[^>]*"
                        + "aria-describedby=\"tk-view-error tk-view-hint\"")
                // The list behind the dialog is the one being saved.
                .contains("<input type=\"hidden\" name=\"query\" value=\"status=OPEN\">");

        views.save(
                new Actor(new UserPermissions(OWNER, false, Set.of(), Map.of()), "Synthetic View Owner"),
                "Taken",
                CaseListQuery.parse(Map.of("status", List.of("OPEN"))));
        for (String[] refused : new String[][] {
            {"Taken", "status=CLOSED", "you already have a view with this name."},
            {"x".repeat(101), "status=CLOSED", "the name is longer than 100 characters."},
            {"Everything", "", "choose a filter or an order first."}
        }) {
            assertThat(mvc.perform(post("/cases/views")
                                    .param("name", refused[0])
                                    .param("query", refused[1])
                                    .with(csrf()))
                            .andExpect(status().isUnprocessableContent())
                            .andReturn()
                            .getResponse()
                            .getContentAsString())
                    .contains(refused[2]);
        }
        mvc.perform(post("/cases/views")
                        .param("name", "Bad")
                        .param("query", "status=GONE")
                        .with(csrf()))
                .andExpect(status().isUnprocessableContent());
        assertThat(views.views(OWNER)).extracting(SavedViewService.View::name).containsExactly("Taken");
    }

    @Test
    @WithAppUser(id = OWNER)
    void theManagePageListsOwnViewsAndDeletesOnlyThem() throws Exception {
        long mine = ((Created) views.save(
                        new Actor(new UserPermissions(OWNER, false, Set.of(), Map.of()), "Synthetic View Owner"),
                        "Mine to delete",
                        CaseListQuery.parse(Map.of("q", List.of("heron")))))
                .view()
                .id();

        assertThat(html(get("/cases/views")))
                .contains("<h1>Saved views</h1>", "<caption>Your saved views, by name</caption>")
                .contains("<th scope=\"row\"><a href=\"/cases?q=heron\">Mine to delete</a></th>")
                .contains("<form method=\"post\" action=\"/cases/views/" + mine + "/delete\">")
                .contains("class=\"visually-hidden\"> Mine to delete</span>")
                .doesNotContain("Their private view", "/cases/views/" + othersView + "/");

        mvc.perform(post("/cases/views/" + othersView + "/delete").with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/cases/views/not-an-id/delete").with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/cases/views/" + mine + "/delete")).andExpect(status().isForbidden());
        assertThat(views.views(OTHER)).hasSize(1);
        assertThat(views.views(OWNER)).hasSize(1);

        mvc.perform(post("/cases/views/" + mine + "/delete").with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/cases/views"));
        assertThat(views.views(OWNER)).isEmpty();
        assertThat(html(get("/cases/views")))
                .contains("You have no saved views.")
                .doesNotContain("<table");
        assertThat(html(get("/cases"))).doesNotContain("<summary>Saved views</summary>");
    }

    @Test
    @WithAppUser(id = OWNER)
    void savingWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(post("/cases/views").param("name", "No token").param("query", "status=OPEN"))
                .andExpect(status().isForbidden());
        assertThat(views.views(OWNER)).isEmpty();
    }

    @Test
    void anonymousRequestsGoToTheIdentityProvider() throws Exception {
        mvc.perform(get("/cases/views")).andExpect(status().isFound());
        mvc.perform(post("/cases/views")
                        .param("name", "x")
                        .param("query", "status=OPEN")
                        .with(csrf()))
                .andExpect(status().isFound());
        mvc.perform(post("/cases/views/" + othersView + "/delete").with(csrf())).andExpect(status().isFound());
        assertThat(views.views(OTHER)).hasSize(1);
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private void user(long id, String name) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                id,
                "saved-view-subject-" + id,
                name);
    }
}
