// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import io.trailkeep.e2e.pages.ShellPage;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.GlobalRole;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
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
 * The Failed mail screen (#318, BUILD_PLAN §10.2) in a real browser: the page as the server renders it, with failed
 * mail, with the result notice after a re-queue, and empty, has 0 axe violations and valid HTML, names the page's
 * heading, labels every checkbox and runs no script of its own. Rows are rolled back after each test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OutboxResendAccessibilityIT {

    private static final long ADMIN = 986_501L;

    private static @Nullable Playwright playwright;
    private static @Nullable Browser browser;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void launchBrowser() {
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch();
        } catch (PlaywrightException e) {
            if (System.getenv("CI") != null) {
                throw e;
            }
            Assumptions.abort("No Playwright browser in this environment ("
                    + String.valueOf(e.getMessage()).lines().findFirst().orElse("")
                    + "); CI runs this test and fails without one");
        }
    }

    @AfterAll
    static void closeBrowser() {
        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    void seed() {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, 'outbox-axe-admin', 'Synthetic Outbox Admin', 'outbox-axe@example.invalid',"
                        + " FALSE, CURRENT_TIMESTAMP)",
                ADMIN);
        jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, 'COMPLIANCE_ADMIN')", ADMIN);
        jdbc.update("DELETE FROM NOTIFICATION_OUTBOX");
    }

    @Test
    @WithAppUser(id = ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void theScreenHasNoAxeViolationsWithFailedMailWithTheResultNoticeAndEmpty() throws Exception {
        assertAccessible(render("/admin/outbox"), 0);
        Timestamp at = Timestamp.from(Instant.now().minusSeconds(90));
        for (long id = 986_601L; id < 986_603L; id++) {
            jdbc.update(
                    "INSERT INTO NOTIFICATION_OUTBOX (ID, RECIPIENT, TEMPLATE_ID, SUBJECT, BODY_TEXT, BODY_HTML,"
                            + " STATUS, ATTEMPTS, NEXT_ATTEMPT_AT, LAST_ERROR, CREATED_AT) VALUES (?,"
                            + " 'reader@example.invalid', 'comment-added', 'Synthetic', 'Synthetic body',"
                            + " '<p>Synthetic body</p>', 'FAILED', 10, ?, '550 mailbox unavailable', ?)",
                    id,
                    at,
                    at);
        }

        assertAccessible(render("/admin/outbox"), 2);
        assertAccessible(render("/admin/outbox?requeued=1&skipped=1"), 2);
    }

    private static String stylesheet(String path) {
        try {
            return Files.readString(Path.of("src/main/resources/static", path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String render(String path) throws Exception {
        return mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static void assertAccessible(String html, int checkboxes) {
        try (Page page = Objects.requireNonNull(browser).newPage()) {
            ShellPage.setContent(page, html);
            // The page is set as content, so its stylesheet links do not load: give it the same CSS (target size and
            // contrast are checked against it).
            for (String sheet : List.of("vendor/bootstrap/bootstrap.min.css", "css/app.css")) {
                page.addStyleTag(new Page.AddStyleTagOptions().setContent(stylesheet(sheet)));
            }
            // Styles that arrive after first paint would otherwise be measured half-way through a colour transition.
            page.addStyleTag(new Page.AddStyleTagOptions().setContent("*{transition:none!important}"));

            assertThat(new ShellPage(page).heading()).isEqualTo("Failed mail");
            assertThat(page.locator("input[type=checkbox]").count()).isEqualTo(checkboxes);
            assertThat(page.locator("label[for^=tk-outbox-]").count()).isEqualTo(checkboxes);
            assertThat(page.locator("main script").count()).isZero();
            assertThat(new ShellPage(page).axeViolations()).isEmpty();
        }
    }
}
