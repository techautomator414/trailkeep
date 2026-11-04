// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import io.trailkeep.e2e.pages.ShellPage;
import io.trailkeep.e2e.pages.SignInPage;
import io.trailkeep.testsupport.KeycloakDevRealm;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
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
 * The shell and error pages in a real browser after a real OIDC login: axe-core finds 0 WCAG 2.x A/AA violations,
 * the console shows no CSP violation, and the skip link is the first tab stop (WP-05 acceptance).
 *
 * <p>Chromium comes from Playwright ({@code PLAYWRIGHT_BROWSERS_PATH} where pre-installed, otherwise downloaded on
 * first use). If no browser can be launched the test is skipped with the reason, except when {@code CI} is set,
 * where it fails: CI must always run it.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import({PostgresTestcontainersConfiguration.class, BoomController.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class ShellAccessibilityIT {

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
        BrowserContext context = browser.newContext();
        page = context.newPage();
        // CSP violations are reported on the console; uncaught script errors as page errors. (The browser also logs
        // a console error for the document's own 404/500 status, which is the point of those pages, not a problem.)
        page.onConsoleMessage(message -> {
            if (message.text().contains("Content Security Policy")
                    || message.text().contains("Refused to")) {
                consoleProblems.add(message.text());
            }
        });
        page.onPageError(error -> consoleProblems.add(error));
        page.navigate(KEYCLOAK.app("/"));
        new SignInPage(page).signIn("alpha.analyst", KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        page.waitForLoadState();
        consoleProblems.clear();
    }

    private Response open(String path) {
        return Objects.requireNonNull(page.navigate(KEYCLOAK.app(path)), path);
    }

    @Test
    void dashboardHasNoAccessibilityViolationsAndNoCspViolations() {
        Response response = open("/");
        ShellPage shell = new ShellPage(page);

        assertThat(response.status()).isEqualTo(200);
        assertThat(shell.heading()).isEqualTo("Dashboard");
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }

    @Test
    void htmxRunsWithTheFixedConfigurationAndThereIsNoBootstrapJavaScript() {
        open("/");

        Object config = page.evaluate("() => JSON.stringify({allowEval: htmx.config.allowEval, "
                + "allowScriptTags: htmx.config.allowScriptTags, selfRequestsOnly: htmx.config.selfRequestsOnly, "
                + "includeIndicatorStyles: htmx.config.includeIndicatorStyles, "
                + "historyCacheSize: htmx.config.historyCacheSize, "
                + "refreshOnHistoryMiss: htmx.config.refreshOnHistoryMiss})");

        assertThat(config)
                .isEqualTo("{\"allowEval\":false,\"allowScriptTags\":false,\"selfRequestsOnly\":true,"
                        + "\"includeIndicatorStyles\":false,\"historyCacheSize\":0,\"refreshOnHistoryMiss\":true}");
        assertThat(page.evaluate("() => typeof window.bootstrap")).isEqualTo("undefined");
        assertThat(page.evaluate("() => document.querySelectorAll('style').length"))
                .isEqualTo(0);
    }

    @Test
    void skipLinkIsTheFirstTabStop() {
        page.navigate(KEYCLOAK.app("/"));

        assertThat(new ShellPage(page).firstTabStopClass()).contains("tk-skip-link");
    }

    @Test
    void sessionDialogOpenHasNoAccessibilityViolations() {
        page.navigate(KEYCLOAK.app("/"));
        ShellPage shell = new ShellPage(page);

        shell.openSessionDialog();

        assertThat(page.isVisible("#tk-session-dialog")).isTrue();
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }

    @Test
    void notFoundPageHasNoAccessibilityViolations() {
        Response response = open("/no-such-page");
        ShellPage shell = new ShellPage(page);

        assertThat(response.status()).isEqualTo(404);
        assertThat(shell.heading()).isEqualTo("Page not found");
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }

    @Test
    void serverErrorPageHasNoAccessibilityViolationsAndNoDetails() {
        Response response = open("/test-only/boom");
        ShellPage shell = new ShellPage(page);

        assertThat(response.status()).isEqualTo(500);
        assertThat(shell.heading()).isEqualTo("Something went wrong");
        assertThat(page.content()).doesNotContain("unexpected failure", "IllegalStateException");
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }
}
