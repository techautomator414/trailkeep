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
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.KeycloakDevRealm;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.TeamRole;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
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
 * Golden path 1 (BUILD_PLAN §4.2, §13 "E2E"): the dev realm's {@code alpha.analyst} signs in, opens the create form
 * from the dashboard, meets the error summary and the PHI warning, saves with {@code Ctrl+S} and lands on the new
 * case page with the toast. Every state (form, error summary, PHI warning, case page, and the form for someone with
 * no team) has 0 axe violations and no CSP violation in the console. The team, its membership and a case type are
 * synthetic rows seeded once the user exists. Browser handling follows {@link ShellAccessibilityIT}.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class GoldenPathOneIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String ALPHA_ANALYST = "00000000-0000-4000-8000-000000000002";

    private static Playwright playwright;
    private static Browser browser;

    private final List<String> consoleProblems = new CopyOnWriteArrayList<>();
    private Page page;

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
    void openBrowser() {
        page = browser.newContext().newPage();
        page.onConsoleMessage(message -> {
            if (message.text().contains("Content Security Policy")
                    || message.text().contains("Refused to")) {
                consoleProblems.add(message.text());
            }
        });
        page.onPageError(error -> consoleProblems.add(error));
    }

    @Test
    void anAnalystCreatesACaseAndLandsOnItsPage() {
        signIn("alpha.analyst");
        Long userId = jdbc.queryForObject("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, ALPHA_ANALYST);
        CaseSeed seed = new CaseSeed(jdbc);
        long team = seed.team();
        seed.member(Objects.requireNonNull(userId), team, TeamRole.ANALYST);
        final String caseType = Long.toString(seed.caseType());

        page.navigate(KEYCLOAK.app("/"));
        page.click("main a[href='/cases/new']");
        page.waitForURL(url -> url.endsWith("/cases/new"));
        assertClean("New case");

        page.selectOption("#caseTypeId", caseType);
        page.selectOption("#teamId", Long.toString(team));
        page.fill("#title", "   ");
        page.fill("#description", "Synthetic description of a synthetic matter");
        assertThat(submit().status()).isEqualTo(422);
        page.waitForSelector("[data-tk-error-summary]");
        new ShellPage(page).waitForErrorSummaryFocus();
        assertThat(page.evaluate("() => document.activeElement.hasAttribute('data-tk-error-summary')"))
                .isEqualTo(true);
        assertClean("New case");

        page.fill("#title", "Synthetic 123-45-6789");
        assertThat(submit().status()).isEqualTo(422);
        page.waitForSelector("#titleConfirmed");
        assertThat(page.textContent("[data-tk-error-summary]")).contains("social security number");
        assertClean("New case");

        page.fill("#title", "Synthetic golden path case");
        page.focus("#description");
        page.keyboard().press("Control+S");
        page.waitForURL(url -> url.matches(".*/cases/CASE-\\d{4}-\\d{6,}\\?created$"));
        assertClean("Synthetic golden path case");
        assertThat(page.textContent("[data-tk-toast]")).contains("was created");
        assertThat(page.textContent("main")).contains("Seed Team " + team, "Synthetic description of a synthetic");
    }

    @Test
    void someoneWithoutATeamIsToldTheyCannotCreateCases() {
        signIn("compliance.admin");
        Response form = Objects.requireNonNull(page.navigate(KEYCLOAK.app("/cases/new")));
        assertThat(form.status()).isEqualTo(200);
        assertThat(page.textContent("main")).contains("You cannot create cases");
        assertClean("New case");
    }

    private void signIn(String username) {
        page.navigate(KEYCLOAK.app("/"));
        new SignInPage(page).signIn(username, KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        page.waitForLoadState();
        consoleProblems.clear();
    }

    private Response submit() {
        return page.waitForResponse(
                response -> response.request().method().equals("POST"),
                () -> page.click("main form button[type=submit]"));
    }

    private void assertClean(String heading) {
        page.waitForLoadState();
        ShellPage shell = new ShellPage(page);
        assertThat(shell.heading()).contains(heading);
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }
}
