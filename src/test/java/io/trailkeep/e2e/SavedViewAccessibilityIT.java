// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import io.trailkeep.e2e.pages.CasesListPage;
import io.trailkeep.e2e.pages.ShellPage;
import io.trailkeep.e2e.pages.SignInPage;
import io.trailkeep.testsupport.KeycloakDevRealm;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.util.List;
import java.util.UUID;
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
 * Saved views (WP-15 part 3) in a real browser after a real OIDC login as the dev realm's {@code compliance.admin},
 * driven from the keyboard: on a searched list, Tab to "Save view", Enter opens the dialog on its name field, type a
 * name and press Enter. The page comes back as the saved view's list, the view is listed under "Cases" in the
 * navigation (a native disclosure), and Enter on it applies the same URL. On the manage page, Delete removes it.
 * axe finds 0 violations with the dialog closed and open, with the navigation open, and on the manage page before
 * and after the delete, with no CSP problem in the console.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class SavedViewAccessibilityIT {

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
    void signInAsComplianceAdmin() {
        page = browser.newContext().newPage();
        page.onConsoleMessage(message -> {
            if (message.text().contains("Content Security Policy")
                    || message.text().contains("Refused to")) {
                consoleProblems.add(message.text());
            }
        });
        page.onPageError(error -> consoleProblems.add(error));
        page.navigate(KEYCLOAK.app("/"));
        new SignInPage(page).signIn("compliance.admin", KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        page.waitForLoadState();
        consoleProblems.clear();
    }

    @Test
    void viewsAreSavedAppliedAndDeletedFromTheKeyboardWithNoViolations() {
        String word = "dunlin" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        final String name = "Dunlins " + word.substring(6);
        page.navigate(KEYCLOAK.app("/cases?q=" + word + "&sort=title"));
        page.waitForLoadState();
        ShellPage shell = new ShellPage(page);
        CasesListPage list = new CasesListPage(page);
        assertThat(shell.axeViolations()).isEmpty();

        // Tab from the search box past Search and Filters to "Save view"; Enter opens the dialog on its name field.
        page.locator("#tk-q").focus();
        list.tabTo("tk-save-open", 3);
        list.pressAndWaitForFocusOn("Enter", "tk-view-name");
        assertThat(page.evaluate("() => document.getElementById('tk-save-dialog').matches(':modal')"))
                .isEqualTo(true);
        assertThat(shell.axeViolations()).isEmpty();
        page.keyboard().type(name);
        String applied = "/cases?q=" + word + "&sort=title&dir=asc";
        page.keyboard().press("Enter");
        // The POST answers with a redirect to the view's canonical list, which spells out the order.
        page.waitForURL(url -> url.endsWith(applied));
        page.waitForLoadState();

        // From the plain list, the view is listed under Cases: Enter opens the disclosure, Tab reaches the view, Enter
        // applies it.
        page.navigate(KEYCLOAK.app("/cases"));
        page.waitForLoadState();
        page.locator(".tk-views summary").focus();
        page.keyboard().press("Enter");
        assertThat(page.locator(".tk-views a", new Page.LocatorOptions().setHasText(name))
                        .getAttribute("href"))
                .isEqualTo(applied);
        assertThat(shell.axeViolations()).isEmpty();
        tabToText(name, 25);
        page.keyboard().press("Enter");
        page.waitForURL(url -> url.endsWith(applied));
        page.waitForLoadState();
        assertThat(String.valueOf(page.inputValue("#tk-q"))).isEqualTo(word);

        // The manage page lists it with a Delete button, whose name includes the view's.
        page.navigate(KEYCLOAK.app("/cases/views"));
        page.waitForLoadState();
        assertThat(shell.heading()).isEqualTo("Saved views");
        assertThat(shell.axeViolations()).isEmpty();
        page.locator("main a").first().focus();
        tabToText("Delete " + name, 60);
        // The delete posts and comes back to the same URL, so wait for the redirected page itself.
        page.waitForResponse(
                response -> response.url().endsWith("/cases/views")
                        && "GET".equals(response.request().method()),
                () -> page.keyboard().press("Enter"));
        page.waitForLoadState();
        assertThat(page.url()).endsWith("/cases/views");
        assertThat(page.textContent("main")).doesNotContain(name);
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }

    /** Presses Tab until the focused element's text (its accessible text, here) is the given one. */
    private void tabToText(String text, int presses) {
        for (int i = 1; i <= presses; i++) {
            page.keyboard().press("Tab");
            Object focused = page.evaluate("() => document.activeElement.textContent.replace(/\\s+/g, ' ').trim()");
            if (text.equals(focused)) {
                return;
            }
        }
        throw new AssertionError("Tab never reached " + text);
    }
}
