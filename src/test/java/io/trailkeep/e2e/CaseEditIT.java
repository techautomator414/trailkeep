// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import io.trailkeep.casefile.CaseChanges;
import io.trailkeep.casefile.CaseFields;
import io.trailkeep.casefile.CasePriority;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.e2e.pages.ShellPage;
import io.trailkeep.e2e.pages.SignInPage;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.KeycloakDevRealm;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * Edit in a real browser (WP-12, BUILD_PLAN §4.3, §4.8): the dev realm's {@code alpha.analyst} opens Edit from the
 * case page, meets the error summary, then loses a race to a colleague's save and gets the 409 conflict panel with
 * their input still in the fields; Reload loads the current version, {@code Ctrl+S} saves it, and the case page shows
 * the notice once. Every state (case page with Edit, form, error summary, conflict, notice) has 0 axe violations and
 * no CSP violation in the console. Browser handling follows {@link ShellAccessibilityIT}.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class CaseEditIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String ALPHA_ANALYST = "00000000-0000-4000-8000-000000000002";

    private static Playwright playwright;
    private static Browser browser;

    private final List<String> consoleProblems = new CopyOnWriteArrayList<>();
    private Page page;

    @Autowired
    private CaseService cases;

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
    void anAnalystLosesARaceReloadsAndSaves() {
        page.navigate(KEYCLOAK.app("/"));
        new SignInPage(page).signIn("alpha.analyst", KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        page.waitForLoadState();
        consoleProblems.clear();
        long analyst = Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, ALPHA_ANALYST));
        CaseSeed seed = new CaseSeed(jdbc);
        long team = seed.team();
        seed.member(analyst, team, TeamRole.ANALYST);
        long colleague = seed.user();
        seed.member(colleague, team, TeamRole.ANALYST);
        CaseNumber number = cases.create(actor(analyst, team, "Synthetic Analyst"), fields(seed, team, analyst));

        Response casePage = Objects.requireNonNull(page.navigate(KEYCLOAK.app("/cases/" + number)));
        assertThat(casePage.status()).isEqualTo(200);
        assertClean("Synthetic edit case");
        page.click("main a[href='/cases/" + number + "/edit']");
        page.waitForURL(url -> url.endsWith("/edit"));
        assertClean("Edit " + number);

        page.fill("#title", " ");
        assertThat(submit().status()).isEqualTo(422);
        page.waitForSelector("[data-tk-error-summary]");
        new ShellPage(page).waitForErrorSummaryFocus();
        assertThat(page.evaluate("() => document.activeElement.hasAttribute('data-tk-error-summary')"))
                .isEqualTo(true);
        assertClean("Edit " + number);

        cases.edit(
                actor(colleague, team, "Seed User " + colleague),
                number.toString(),
                0,
                new CaseChanges("Colleague title", CasePriority.HIGH, null, "Colleague description"));
        page.fill("#title", "My synthetic title");
        assertThat(submit().status()).isEqualTo(409);
        page.waitForSelector("[data-tk-error-summary]");
        assertThat(page.textContent("[data-tk-error-summary]"))
                .contains("This case was updated by Seed User " + colleague);
        assertThat(page.inputValue("#title")).isEqualTo("My synthetic title");
        new ShellPage(page).waitForErrorSummaryFocus();
        assertThat(page.evaluate("() => document.activeElement.hasAttribute('data-tk-error-summary')"))
                .isEqualTo(true);
        assertClean("Edit " + number);

        page.click("[data-tk-error-summary] a");
        page.waitForSelector("button[type=submit]");
        assertThat(page.inputValue("#title")).isEqualTo("Colleague title");
        page.fill("#title", "My synthetic title");
        page.focus("#description");
        page.keyboard().press("Control+S");
        page.waitForURL(url -> url.endsWith("/cases/" + number));
        assertClean("My synthetic title");
        assertThat(page.textContent("[data-tk-toast]")).contains("Your changes were saved.");

        page.reload();
        assertClean("My synthetic title");
        assertThat(page.querySelector("[data-tk-toast]")).isNull();
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

    private static CaseService.Actor actor(long userId, long team, String name) {
        return new CaseService.Actor(
                new UserPermissions(userId, false, Set.of(), Map.of(team, TeamRole.ANALYST)), name);
    }

    private static CaseFields fields(CaseSeed seed, long team, long owner) {
        return new CaseFields(
                seed.caseType(),
                "Synthetic edit case",
                CasePriority.NORMAL,
                LocalDate.now(ZoneOffset.UTC).minusDays(2),
                team,
                owner,
                "Synthetic subject",
                "Synthetic description",
                Sensitivity.STANDARD);
    }
}
