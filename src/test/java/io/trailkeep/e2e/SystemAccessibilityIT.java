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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The System screen in a real browser after a real OIDC login as {@code system.admin} (WP-25): reached from the
 * Users page by keyboard, it has one h1, a captioned jobs table with row headers, the outbox as a description list and
 * the scanner's state in text, and "send me a test" works from the keyboard; axe finds 0 violations before and
 * after, with no CSP problem in the console.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class SystemAccessibilityIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();

    private static Playwright playwright;
    private static Browser browser;

    private final List<String> consoleProblems = new CopyOnWriteArrayList<>();
    private Page page;

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
    void signIn() {
        page = browser.newContext().newPage();
        page.onConsoleMessage(message -> {
            if (message.text().contains("Content Security Policy")
                    || message.text().contains("Refused to")) {
                consoleProblems.add(message.text());
            }
        });
        page.onPageError(error -> consoleProblems.add(error));
        page.navigate(KEYCLOAK.app("/"));
        new SignInPage(page).signIn("system.admin", KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        page.waitForLoadState();
        consoleProblems.clear();
    }

    @AfterEach
    void closeContext() {
        page.context().close();
    }

    @Test
    void theSystemScreenIsReachedByKeyboardAndHasNoViolations() {
        page.navigate(KEYCLOAK.app("/admin/users"));
        page.waitForLoadState();
        page.focus("a[href='/admin/system']");
        page.keyboard().press("Enter");
        page.waitForURL("**/admin/system");
        page.waitForLoadState();

        ShellPage shell = new ShellPage(page);
        assertThat(shell.heading()).isEqualTo("System");
        assertThat(page.locator("table caption").textContent()).isEqualTo("Scheduled jobs, by name");
        assertThat(page.locator("tbody th[scope=row]").count()).isGreaterThanOrEqualTo(4);
        assertThat(page.textContent("main")).contains("Waiting to be sent", "clamd does not answer");
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();

        // "Send me a test" from the keyboard: choose a mail, Tab to the button, Enter; the outcome is announced.
        page.focus("#tk-test-template");
        page.keyboard().press("ArrowDown");
        page.keyboard().press("Tab");
        page.keyboard().press("Enter");
        page.waitForURL("**/admin/system?test=*");
        page.waitForLoadState();
        assertThat(page.locator("[data-tk-toast]").textContent()).containsAnyOf("Test mail queued", "Not sent");
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }
}
