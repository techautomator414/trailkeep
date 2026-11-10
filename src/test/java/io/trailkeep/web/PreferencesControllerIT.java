// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
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
 * The preferences page over HTTP (WP-27): the shortcuts switch is on for a person who never saved it, the layout says
 * so to {@code app.js} ({@code data-tk-shortcuts}), saving needs CSRF (403 without) and acts only on the signed-in
 * person, turning it off and on is audited as {@code PREFERENCES_CHANGED} with the old and new value, saving the value
 * the person already has writes nothing, another person's row is never touched, and anonymous requests go to the
 * identity provider.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PreferencesControllerIT {

    private static final long PERSON = 986_001L;
    private static final long OTHER = 986_002L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void seed() {
        user(PERSON);
        user(OTHER);
    }

    @Test
    @WithAppUser(id = PERSON)
    void shortcutsAreOnByDefaultAndTheLayoutSaysSo() throws Exception {
        assertThat(html("/preferences"))
                .contains("<h1>Preferences</h1>", "id=\"tk-pref-shortcuts\"", "checked=\"checked\"")
                .contains("data-tk-shortcuts=\"on\"")
                .doesNotContain("Preferences saved.");
        assertThat(rows(PERSON)).isEmpty();
    }

    @Test
    @WithAppUser(id = PERSON)
    void turningShortcutsOffAndOnIsAuditedAndTheLayoutFollows() throws Exception {
        mvc.perform(post("/preferences")).andExpect(status().isForbidden());

        mvc.perform(post("/preferences").with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/preferences?saved"));
        assertThat(rows(PERSON))
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("SHORTCUTS_ENABLED", false));
        assertThat(html("/preferences?saved"))
                .contains("data-tk-shortcuts=\"off\"", "Preferences saved.")
                .doesNotContain("checked=\"checked\"");
        assertThat(html("/")).contains("data-tk-shortcuts=\"off\"");
        assertThat(changes(PERSON)).singleElement().satisfies(event -> {
            assertThat(event.get("OLD_VALUES_JSON")).asString().contains("true");
            assertThat(event.get("NEW_VALUES_JSON")).asString().contains("false");
        });

        mvc.perform(post("/preferences").param("shortcuts", "on").with(csrf())).andExpect(status().isFound());
        assertThat(rows(PERSON))
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("SHORTCUTS_ENABLED", true));
        assertThat(html("/preferences")).contains("data-tk-shortcuts=\"on\"", "checked=\"checked\"");
        assertThat(changes(PERSON)).hasSize(2);
    }

    @Test
    @WithAppUser(id = PERSON)
    void savingTheValueThePersonAlreadyHasWritesNothingAndOnlyTheirRowChanges() throws Exception {
        mvc.perform(post("/preferences").param("shortcuts", "on").with(csrf())).andExpect(status().isFound());
        assertThat(rows(PERSON)).isEmpty();
        assertThat(changes(PERSON)).isEmpty();

        mvc.perform(post("/preferences").param("userId", String.valueOf(OTHER)).with(csrf()))
                .andExpect(status().isFound());
        assertThat(rows(OTHER)).isEmpty();
        assertThat(rows(PERSON)).hasSize(1);
        assertThat(changes(OTHER)).isEmpty();
    }

    @Test
    void anonymousRequestsGoToTheIdentityProvider() throws Exception {
        mvc.perform(get("/preferences")).andExpect(status().isFound());
        mvc.perform(post("/preferences").with(csrf())).andExpect(status().isFound());
    }

    private String html(String path) throws Exception {
        return mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private List<Map<String, Object>> rows(long userId) {
        entityManager.flush();
        return jdbc.queryForList("SELECT * FROM USER_PREFERENCE WHERE USER_ID = ?", userId);
    }

    private List<Map<String, Object>> changes(long userId) {
        entityManager.flush();
        return jdbc.queryForList(
                "SELECT * FROM AUDIT_EVENT WHERE ACTION = 'PREFERENCES_CHANGED' AND ENTITY_ID = ? ORDER BY ID", userId);
    }

    private void user(long id) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                id,
                "preferences-subject-" + id,
                "Synthetic Preferences User " + id);
    }
}
