// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.notification.UserAlertService;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import java.sql.Timestamp;
import java.time.Instant;
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
 * The Alerts page over HTTP (WP-17 part c): the navigation's "Alerts" link carries the unread count in its name
 * ("Alerts, 2 unread") and a badge; {@code /alerts} lists the person's own alerts newest first and reading it marks
 * nothing read; "Mark read" and "Delete" need CSRF (403 without), act only on the person's own alerts (404 for
 * another person's or an unknown id) and come back to the page; anonymous requests go to the identity provider.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AlertControllerIT {

    static final long OWNER = 982_001L;
    static final long OTHER = 982_002L;
    private static final Instant AT = Instant.parse("2026-11-06T09:30:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserAlertService alerts;

    @BeforeEach
    void seed() {
        user(OWNER);
        user(OTHER);
        alert(982_101L, OWNER, "Older synthetic alert", AT);
        alert(982_102L, OWNER, "Newer synthetic alert", AT.plusSeconds(60));
        alert(982_103L, OTHER, "Their private alert", AT);
    }

    @Test
    @WithAppUser(id = OWNER)
    void theNavigationNamesTheUnreadCountAndThePageListsOwnAlertsNewestFirst() throws Exception {
        assertThat(html("/"))
                .contains("<a href=\"/alerts\" aria-label=\"Alerts, 2 unread\"><span>Alerts</span> "
                        + "<span class=\"badge text-bg-light\">2</span></a>");

        String page = html("/alerts");
        assertThat(page)
                .contains("<h1>Alerts</h1>", "<caption id=\"tk-alerts-caption\">Your alerts, newest first</caption>")
                .contains("<th scope=\"row\"><a href=\"/cases/X#comments\">Newer synthetic alert</a></th>")
                .contains("<time datetime=\"2026-11-06T09:31:00Z\">2026-11-06 09:31</time>")
                .contains("method=\"post\"", "action=\"/alerts/982102/read?page=1\"", "name=\"_csrf\"")
                .contains("class=\"visually-hidden\">: Newer synthetic alert</span>")
                .doesNotContain("Their private alert", "/alerts/982103/");
        assertThat(page.indexOf("Newer synthetic alert")).isLessThan(page.indexOf("Older synthetic alert"));
        assertThat(alerts.unread(OWNER)).isEqualTo(2);
    }

    @Test
    @WithAppUser(id = OWNER)
    void markReadAndDeleteActOnOwnAlertsOnlyAndComeBackToThePage() throws Exception {
        mvc.perform(post("/alerts/982102/read")).andExpect(status().isForbidden());
        mvc.perform(post("/alerts/982103/read").with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/alerts/982103/delete").with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/alerts/not-an-id/delete").with(csrf())).andExpect(status().isNotFound());
        assertThat(alerts.unread(OTHER)).isEqualTo(1);

        mvc.perform(post("/alerts/982102/read").param("page", "1").with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/alerts?page=1"));
        assertThat(alerts.unread(OWNER)).isEqualTo(1);
        assertThat(html("/alerts"))
                .contains("aria-label=\"Alerts, 1 unread\"")
                .doesNotContain("action=\"/alerts/982102/read");

        mvc.perform(post("/alerts/982101/delete").with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/alerts?page=1"));
        assertThat(alerts.unread(OWNER)).isZero();
        assertThat(html("/alerts"))
                .doesNotContain("Older synthetic alert", "aria-label=\"Alerts,")
                .contains("<a href=\"/alerts\"><span>Alerts</span> </a>");
    }

    @Test
    void anonymousRequestsGoToTheIdentityProvider() throws Exception {
        mvc.perform(get("/alerts")).andExpect(status().isFound());
        mvc.perform(post("/alerts/982103/delete").with(csrf())).andExpect(status().isFound());
        assertThat(alerts.unread(OTHER)).isEqualTo(1);
    }

    private String html(String path) throws Exception {
        return mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private void user(long id) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                id,
                "alert-subject-" + id,
                "Synthetic Alert User " + id);
    }

    private void alert(long id, long userId, String message, Instant at) {
        jdbc.update(
                "INSERT INTO USER_ALERT (ID, USER_ID, MESSAGE, LINK, CREATED_AT) VALUES (?, ?, ?, ?, ?)",
                id,
                userId,
                message,
                "/cases/X#comments",
                Timestamp.from(at));
    }
}
