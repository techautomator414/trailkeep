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
 * The single-key shortcuts in a real browser after a real OIDC login as {@code alpha.analyst} (WP-27). On (the
 * default), {@code /} on the dashboard lands on the cases list with the search box focused, and {@code N} opens the
 * new case form; typing the same keys in a field does nothing. Turned off at {@code /preferences} (keyboard only),
 * neither key acts and the setting is kept across a reload. axe finds 0 violations on the preferences page in both
 * states and on the pages the shortcuts reach, with no CSP problem in the console.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class ShortcutsAccessibilityIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String ALPHA_ANALYST = "00000000-0000-4000-8000-000000000002";

    private static Playwright playwright;
    private static Browser browser;

    private final List<String> consoleProblems = new CopyOnWriteArrayList<>();
    private Page page;
    private long analyst;

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
        new SignInPage(page).signIn("alpha.analyst", KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        page.waitForLoadState();
        analyst = Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, ALPHA_ANALYST));
        jdbc.update("DELETE FROM USER_PREFERENCE WHERE USER_ID = ?", analyst);
        consoleProblems.clear();
    }

    @AfterEach
    void removePreferences() {
        page.context().close();
        jdbc.update("DELETE FROM USER_PREFERENCE WHERE USER_ID = ?", analyst);
        jdbc.update("DELETE FROM AUDIT_EVENT WHERE ACTION = 'PREFERENCES_CHANGED' AND ENTITY_ID = ?", analyst);
    }

    @Test
    void slashGoesToTheSearchBoxAndNOpensANewCaseButNotWhileTyping() {
        page.navigate(KEYCLOAK.app("/"));
        assertClean("Dashboard");
        page.keyboard().press("/");
        page.waitForURL("**/cases");
        page.waitForLoadState();
        assertThat(activeId()).isEqualTo("tk-q");
        assertClean("Cases");

        page.keyboard().type("n/");
        assertThat(page.inputValue("#tk-q")).isEqualTo("n/");
        assertThat(page.url()).endsWith("/cases");

        page.navigate(KEYCLOAK.app("/"));
        page.keyboard().press("n");
        page.waitForURL("**/cases/new");
        page.waitForLoadState();
        assertClean("New case");
    }

    @Test
    void turningTheShortcutsOffFromTheKeyboardMakesBothKeysInert() {
        page.navigate(KEYCLOAK.app("/preferences"));
        assertClean("Preferences");
        assertThat(page.isChecked("#tk-pref-shortcuts")).isTrue();

        page.focus("#tk-pref-shortcuts");
        page.keyboard().press("Space");
        page.keyboard().press("Tab");
        page.keyboard().press("Enter");
        page.waitForURL("**/preferences?saved");
        assertClean("Preferences");
        assertThat(page.isChecked("#tk-pref-shortcuts")).isFalse();
        assertThat(page.textContent("main")).contains("Preferences saved.");

        page.navigate(KEYCLOAK.app("/"));
        page.keyboard().press("/");
        page.keyboard().press("n");
        page.waitForTimeout(500);
        assertThat(page.url()).isEqualTo(KEYCLOAK.app("/"));
        assertThat(activeId()).isEmpty();
        assertClean("Dashboard");
    }

    private String activeId() {
        return String.valueOf(page.evaluate("() => document.activeElement.id"));
    }

    private void assertClean(String heading) {
        page.waitForLoadState();
        ShellPage shell = new ShellPage(page);
        assertThat(shell.heading()).contains(heading);
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }
}
