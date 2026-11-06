// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import io.trailkeep.e2e.pages.ShellPage;
import io.trailkeep.e2e.pages.SignInPage;
import io.trailkeep.testsupport.KeycloakDevRealm;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The Alerts page (WP-17 part c) in a real browser after a real OIDC login as the dev realm's {@code beta.reader},
 * driven from the keyboard: Tab from the brand reaches the header link named "Alerts, 2 unread", Enter opens the page,
 * Tab reaches "Mark read" for the newest alert and Enter marks it (the link is now "Alerts, 1 unread"), then Tab
 * reaches "Delete" for the older one and Enter removes it. axe finds 0 violations on the dashboard with the badge and
 * on the page before, between and after, with no CSP problem in the console.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class AlertsAccessibilityIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String READER = "00000000-0000-4000-8000-000000000005";
    private static final String NEWER = "Synthetic Colleague commented on CASE-2026-000042, \"Synthetic title\"";
    private static final String OLDER = "Synthetic Manager commented on CASE-2026-000041, \"Older synthetic title\"";

    private static Playwright playwright;
    private static Browser browser;

    private final List<String> consoleProblems = new CopyOnWriteArrayList<>();
    private Page page;
    private long reader;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        KEYCLOAK.register(registry);
    }

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
    void signInWithTwoAlerts() {
        page = browser.newContext().newPage();
        page.onConsoleMessage(message -> {
            if (message.text().contains("Content Security Policy")
                    || message.text().contains("Refused to")) {
                consoleProblems.add(message.text());
            }
        });
        page.onPageError(error -> consoleProblems.add(error));
        page.navigate(KEYCLOAK.app("/"));
        new SignInPage(page).signIn("beta.reader", KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        page.waitForLoadState();
        reader = Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, READER));
        jdbc.update("DELETE FROM USER_ALERT WHERE USER_ID = ?", reader);
        alert(984_001L, OLDER, Instant.parse("2026-11-06T08:00:00Z"));
        alert(984_002L, NEWER, Instant.parse("2026-11-06T09:00:00Z"));
        consoleProblems.clear();
    }

    @AfterEach
    void removeAlerts() {
        jdbc.update("DELETE FROM USER_ALERT WHERE USER_ID = ?", reader);
    }

    @Test
    void alertsAreReachedMarkedReadAndDeletedFromTheKeyboardWithNoViolations() {
        page.navigate(KEYCLOAK.app("/"));
        page.waitForLoadState();
        ShellPage shell = new ShellPage(page);
        assertThat(shell.axeViolations()).isEmpty();

        page.locator("a.tk-brand").focus();
        tabTo("Alerts, 2 unread", 10);
        page.keyboard().press("Enter");
        page.waitForURL(url -> url.endsWith("/alerts"));
        page.waitForLoadState();
        assertThat(shell.heading()).isEqualTo("Alerts");
        assertThat(page.locator("tbody tr").first().textContent()).contains(NEWER, "Unread");
        assertThat(shell.axeViolations()).isEmpty();

        page.locator("main a").first().focus();
        tabTo("Mark read: " + NEWER, 10);
        submitAndWaitForThePage();
        assertThat(page.locator("a[href='/alerts']").getAttribute("aria-label")).isEqualTo("Alerts, 1 unread");
        assertThat(page.locator("tbody tr").first().textContent()).contains(NEWER, "Read");
        assertThat(shell.axeViolations()).isEmpty();

        page.locator("main a").first().focus();
        tabTo("Delete: " + OLDER, 15);
        submitAndWaitForThePage();
        assertThat(page.textContent("main")).doesNotContain(OLDER).contains(NEWER);
        assertThat(page.locator("a[href='/alerts']").getAttribute("aria-label")).isNull();
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }

    /** Presses Enter on the focused button; the POST redirects back to page 1 of the alerts. */
    private void submitAndWaitForThePage() {
        page.waitForResponse(
                response -> response.url().endsWith("/alerts?page=1")
                        && "GET".equals(response.request().method()),
                () -> page.keyboard().press("Enter"));
        page.waitForLoadState();
    }

    /** Presses Tab until the focused element's accessible name (its aria-label, or its text) is the given one. */
    private void tabTo(String name, int presses) {
        for (int i = 1; i <= presses; i++) {
            page.keyboard().press("Tab");
            Object focused = page.evaluate("() => (document.activeElement.getAttribute('aria-label')"
                    + " || document.activeElement.textContent).replace(/\\s+/g, ' ').trim()");
            if (name.equals(focused)) {
                return;
            }
        }
        throw new AssertionError("Tab never reached " + name);
    }

    private void alert(long id, String message, Instant at) {
        jdbc.update(
                "INSERT INTO USER_ALERT (ID, USER_ID, MESSAGE, LINK, CREATED_AT) VALUES (?, ?, ?, ?, ?)",
                id,
                reader,
                message,
                "/cases",
                Timestamp.from(at));
    }
}
