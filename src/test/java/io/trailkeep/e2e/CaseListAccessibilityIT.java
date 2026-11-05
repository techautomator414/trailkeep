// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import io.trailkeep.casefile.CaseFields;
import io.trailkeep.casefile.CasePriority;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.e2e.pages.CasesListPage;
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
 * The cases list (WP-15 part 1a) in a real browser after a real OIDC login as the dev realm's {@code
 * compliance.admin}, who reads every team's cases, driven from the keyboard: Tab to the Title header, Enter to sort,
 * Enter again to reverse, Tab on to the SLA header. After each htmx swap focus stays on the header used, the URL
 * carries the order, {@code aria-sort} follows, and axe finds 0 violations with no CSP problem in the console. A
 * standard and a restricted case created through {@link CaseService}: the restricted one is listed first (newest)
 * and marked; cases other tests commit only add rows.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class CaseListAccessibilityIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();

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
    void sortingFromTheKeyboardKeepsFocusOnTheHeaderAndHasNoViolations() {
        CaseSeed seed = new CaseSeed(jdbc);
        long team = seed.team();
        long analyst = seed.user();
        seed.member(analyst, team, TeamRole.ANALYST);
        CaseService.Actor actor = new CaseService.Actor(
                new UserPermissions(analyst, false, Set.of(), Map.of(team, TeamRole.ANALYST)), "Synthetic Analyst");
        cases.create(actor, fields(seed, team, analyst, "Synthetic listed standard", Sensitivity.STANDARD));
        final CaseNumber restricted =
                cases.create(actor, fields(seed, team, analyst, "Synthetic listed restricted", Sensitivity.RESTRICTED));

        Response response = Objects.requireNonNull(page.navigate(KEYCLOAK.app("/cases")));
        assertThat(response.status()).isEqualTo(200);
        page.waitForLoadState();
        ShellPage shell = new ShellPage(page);
        CasesListPage list = new CasesListPage(page);
        assertThat(shell.heading()).isEqualTo("Cases");
        assertThat(page.title()).startsWith("Cases");
        assertThat(list.status()).endsWith("cases found.").doesNotStartWith("No");
        assertThat(list.numbers().get(0)).isEqualTo(restricted.toString());
        assertThat(page.textContent("main")).contains("Synthetic listed restricted", "Restricted");
        assertThat(list.sortStates()).containsExactly("Opened=descending");
        assertThat(shell.axeViolations()).isEmpty();

        page.locator("#tk-results-status").focus();
        list.tabTo("tk-sort-title", 4);
        list.pressEnterAndWaitForFocusOn("tk-sort-title");
        assertThat(list.sortStates()).containsExactly("Title=ascending");
        assertThat(page.url()).contains("sort=title", "dir=asc");
        assertThat(shell.axeViolations()).isEmpty();

        list.pressEnterAndWaitForFocusOn("tk-sort-title");
        assertThat(list.sortStates()).containsExactly("Title=descending");
        assertThat(page.url()).contains("sort=title", "dir=desc");

        list.tabTo("tk-sort-due", 6);
        list.pressEnterAndWaitForFocusOn("tk-sort-due");
        assertThat(list.sortStates()).containsExactly("SLA=ascending");
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }

    private static CaseFields fields(CaseSeed seed, long team, long owner, String title, Sensitivity sensitivity) {
        return new CaseFields(
                seed.caseType(),
                title,
                CasePriority.NORMAL,
                LocalDate.now(ZoneOffset.UTC).minusDays(2),
                team,
                owner,
                "Synthetic subject",
                "Synthetic description",
                sensitivity);
    }
}
