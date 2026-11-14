// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
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
 * The team admin screens (WP-09) in a real browser after a real OIDC login as the dev realm's {@code system.admin}:
 * the team list, a team page and the user list (disabling a user), and the first two in their 422 invalid-input
 * state, have 0 axe violations and no CSP violation in the console. Browser handling follows
 * {@link ShellAccessibilityIT}.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class TeamAdminAccessibilityIT {

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
    void signInAsSystemAdmin() {
        page = browser.newContext().newPage();
        page.onConsoleMessage(message -> {
            if (message.text().contains("Content Security Policy")
                    || message.text().contains("Refused to")) {
                consoleProblems.add(message.text());
            }
        });
        page.onPageError(error -> consoleProblems.add(error));
        page.navigate(KEYCLOAK.app("/admin/teams"));
        new SignInPage(page).signIn("system.admin", KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        page.waitForLoadState();
        consoleProblems.clear();
    }

    @Test
    void teamListAndItsInvalidStateHaveNoViolations() {
        Response list = Objects.requireNonNull(page.navigate(KEYCLOAK.app("/admin/teams")));
        assertThat(list.status()).isEqualTo(200);
        assertClean("Teams");

        Response invalid = submit("#name", "   ", "main form button[type=submit]");
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(page.isVisible("[role=alert]")).isTrue();
        assertClean("Teams");
    }

    @Test
    void teamPageAndItsInvalidStateHaveNoViolations() {
        page.navigate(KEYCLOAK.app("/admin/teams"));
        String name = "Accessibility " + UUID.randomUUID().toString().substring(0, 8);
        page.fill("#name", name);
        page.click("main form button[type=submit]");
        page.waitForURL(url -> url.matches(".*/admin/teams/\\d+$"));
        assertClean(name);

        Response invalid = submit("#name", "   ", "main form button[type=submit] >> nth=0");
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(page.isVisible("[role=alert]")).isTrue();
        assertClean(name);
    }

    @Test
    void takenTeamNameIsAFieldErrorOnTheListAndOnTheTeamPage() {
        String taken = createTeam();
        page.navigate(KEYCLOAK.app("/admin/teams"));
        assertNameTaken("Teams", taken, "main form button[type=submit]");

        String other = createTeam();
        assertNameTaken(other, taken, "main form button[type=submit] >> nth=0");
    }

    private String createTeam() {
        page.navigate(KEYCLOAK.app("/admin/teams"));
        String name = "Accessibility " + UUID.randomUUID().toString().substring(0, 8);
        page.fill("#name", name);
        page.click("main form button[type=submit]");
        page.waitForURL(url -> url.matches(".*/admin/teams/\\d+$"));
        return name;
    }

    @Test
    void userListHasNoViolationsAndItsButtonsAreReachableByKeyboard() {
        // A second user must exist: the admin's own row has no Disable button.
        Page other = browser.newContext().newPage();
        other.navigate(KEYCLOAK.app("/"));
        new SignInPage(other).signIn("alpha.reader", KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        other.waitForLoadState();
        other.context().close();

        Response list = Objects.requireNonNull(page.navigate(KEYCLOAK.app("/admin/users")));
        assertThat(list.status()).isEqualTo(200);
        assertClean("Users");

        // Keyboard only: Tab from the page top reaches a row action button (skip link, nav, Teams link, then rows).
        boolean reached = false;
        for (int i = 0; i < 30 && !reached; i++) {
            page.keyboard().press("Tab");
            reached = Boolean.TRUE.equals(page.evaluate(
                    "() => document.activeElement && document.activeElement.matches('main form button[type=submit]')"));
        }
        assertThat(reached)
                .as("a Disable or Enable button takes keyboard focus")
                .isTrue();
        assertThat(page.locator("main form button[type=submit]:focus").innerText())
                .matches("(Disable|Enable) .+");
    }

    /** Submits {@code taken} as the name: refused as a field error, the banner saying so, axe clean. */
    private void assertNameTaken(String heading, String taken, String button) {
        Response refused = submit("#name", taken, button);
        assertThat(refused.status()).isEqualTo(422);
        assertThat(page.locator("[role=alert]").innerText())
                .isEqualTo("Not saved: that name is already taken. See the field marked below.");
        assertThat(page.getAttribute("#name", "aria-invalid")).isEqualTo("true");
        assertThat(page.getAttribute("#name", "aria-describedby")).isEqualTo("name-error");
        assertThat(page.isVisible("#name-error")).isTrue();
        assertClean(heading);
    }

    private Response submit(String field, String value, String button) {
        page.fill(field, value);
        return page.waitForResponse(response -> response.request().method().equals("POST"), () -> page.click(button));
    }

    private void assertClean(String heading) {
        page.waitForLoadState();
        ShellPage shell = new ShellPage(page);
        assertThat(shell.heading()).isEqualTo(heading);
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }
}
