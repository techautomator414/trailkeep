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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
 * The cases list (WP-15 parts 1a and 1b) in a real browser after a real OIDC login as the dev realm's {@code
 * compliance.admin}, who reads every team's cases, driven from the keyboard: Tab to the Title header, Enter to sort,
 * Enter again to reverse, Tab on to the SLA header. After each htmx swap focus stays on the header used, the URL
 * carries the order, {@code aria-sort} follows, and axe finds 0 violations with no CSP problem in the console. A
 * standard and a restricted case created through {@link CaseService}: the restricted one is listed first (newest)
 * and marked; cases other tests commit only add rows. The search walk types a per-run word into the search box and
 * presses Enter: focus moves to the status line with the count, the URL carries {@code q}, sorting keeps the search,
 * and the empty result has no violations either. The filter walk (part 2) tabs to "Filters", opens the dialog with
 * Enter (focus on its first box), tabs to Priority and Apply, and checks focus on the status line, the chips, Escape
 * returning focus to "Filters", and a chip removed from the keyboard; axe runs with the dialog open and closed. The
 * no-JavaScript walk follows the "Filters" link to the dialog rendered in the page and applies it as a plain GET.
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

    @Test
    void searchingFromTheKeyboardMovesFocusToTheCountAndHasNoViolations() {
        String word = "Tern" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        CaseSeed seed = new CaseSeed(jdbc);
        long team = seed.team();
        long analyst = seed.user();
        seed.member(analyst, team, TeamRole.ANALYST);
        CaseService.Actor actor = new CaseService.Actor(
                new UserPermissions(analyst, false, Set.of(), Map.of(team, TeamRole.ANALYST)), "Synthetic Analyst");
        final CaseNumber bravo =
                cases.create(actor, fields(seed, team, analyst, "Bravo " + word, Sensitivity.STANDARD));
        final CaseNumber alpha =
                cases.create(actor, fields(seed, team, analyst, "Alpha " + word, Sensitivity.RESTRICTED));
        page.navigate(KEYCLOAK.app("/cases"));
        page.waitForLoadState();
        ShellPage shell = new ShellPage(page);
        CasesListPage list = new CasesListPage(page);
        assertThat(shell.axeViolations()).isEmpty();

        list.search(word.toLowerCase(Locale.ROOT));
        assertThat(list.status()).isEqualTo("2 cases found.");
        assertThat(page.url()).contains("q=" + word.toLowerCase(Locale.ROOT));
        assertThat(list.numbers()).containsExactly(alpha.toString(), bravo.toString());
        assertThat(shell.axeViolations()).isEmpty();

        list.tabTo("tk-sort-title", 4);
        list.pressEnterAndWaitForFocusOn("tk-sort-title");
        assertThat(list.numbers()).containsExactly(alpha.toString(), bravo.toString());
        assertThat(page.url()).contains("q=" + word.toLowerCase(Locale.ROOT), "sort=title");

        page.navigate(KEYCLOAK.app("/cases?q=Nothing" + word));
        page.waitForLoadState();
        assertThat(list.status()).isEqualTo("No cases found.");
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }

    @Test
    void theFilterDialogWorksFromTheKeyboardAndHasNoViolationsOpenOrClosed() {
        String word = "Plover" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        CaseSeed seed = new CaseSeed(jdbc);
        long team = seed.team();
        long analyst = seed.user();
        seed.member(analyst, team, TeamRole.ANALYST);
        CaseService.Actor actor = new CaseService.Actor(
                new UserPermissions(analyst, false, Set.of(), Map.of(team, TeamRole.ANALYST)), "Synthetic Analyst");
        final CaseNumber urgent = cases.create(
                actor, fields(seed, team, analyst, "Urgent " + word, Sensitivity.STANDARD, CasePriority.HIGH));
        cases.create(actor, fields(seed, team, analyst, "Routine " + word, Sensitivity.STANDARD));
        page.navigate(KEYCLOAK.app("/cases?q=" + word));
        page.waitForLoadState();
        ShellPage shell = new ShellPage(page);
        CasesListPage list = new CasesListPage(page);
        assertThat(list.status()).isEqualTo("2 cases found.");
        assertThat(list.filtersOpen()).isFalse();
        assertThat(shell.axeViolations()).isEmpty();

        // Tab from the search box past its button to "Filters"; Enter opens the dialog on its first control.
        page.locator("#tk-q").focus();
        list.tabTo("tk-filters-open", 2);
        list.pressAndWaitForFocusOn("Enter", "tk-status-OPEN");
        assertThat(list.filtersShownModal()).isTrue();
        assertThat(shell.axeViolations()).isEmpty();

        // Tab through the boxes to Priority, choose High with the arrow keys, Tab on to Apply and press Enter.
        list.tabTo("tk-priority", 12);
        page.keyboard().press("ArrowDown");
        page.keyboard().press("ArrowDown");
        assertThat(list.value("tk-priority")).isEqualTo("HIGH");
        list.tabTo("tk-filters-apply", 16);
        list.pressEnterAndWaitForFocusOn("tk-results-status");
        assertThat(list.filtersOpen()).isFalse();
        assertThat(list.status()).isEqualTo("1 case found.");
        assertThat(list.numbers()).containsExactly(urgent.toString());
        assertThat(page.url()).contains("q=" + word, "priority=HIGH");
        assertThat(list.chips()).containsExactly("Remove filter: Search " + word, "Remove filter: Priority High");
        assertThat(shell.axeViolations()).isEmpty();

        // Escape closes the dialog and focus returns to "Filters".
        page.locator("#tk-filters-open").focus();
        list.pressAndWaitForFocusOn("Enter", "tk-status-OPEN");
        list.pressAndWaitForFocusOn("Escape", "tk-filters-open");
        assertThat(list.filtersOpen()).isFalse();

        // Removing the priority chip lists both again, and the dialog's Priority is back to Any.
        list.tabToLabel("Remove filter: Priority High", 3);
        list.pressEnterAndWaitForFocusOn("tk-results-status");
        assertThat(list.status()).isEqualTo("2 cases found.");
        assertThat(page.url()).doesNotContain("priority=");
        assertThat(list.chips()).containsExactly("Remove filter: Search " + word);
        assertThat(list.value("tk-priority")).isEmpty();
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }

    @Test
    void withoutJavaScriptTheFiltersLinkShowsTheDialogInThePageAndApplyIsAPlainGet() {
        // The dialog as the server renders it for filters=1, checked with axe while scripts run but leave it alone.
        page.navigate(KEYCLOAK.app("/cases?status=CLOSED&filters=1"));
        page.waitForLoadState();
        CasesListPage list = new CasesListPage(page);
        assertThat(list.filtersOpen()).isTrue();
        assertThat(list.filtersShownModal()).isFalse();
        assertThat(new ShellPage(page).axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();

        // The same session in a browser with JavaScript off.
        Page plain = browser.newContext(new Browser.NewContextOptions()
                        .setJavaScriptEnabled(false)
                        .setStorageState(page.context().storageState()))
                .newPage();
        plain.navigate(KEYCLOAK.app("/cases?status=CLOSED"));
        assertThat(plain.isVisible("#tk-status-OPEN")).isFalse();
        plain.click("#tk-filters-open");
        plain.waitForLoadState();
        assertThat(plain.url()).contains("status=CLOSED", "filters=1");
        assertThat(plain.isVisible("#tk-status-OPEN")).isTrue();
        assertThat(plain.isChecked("#tk-status-CLOSED")).isTrue();
        plain.check("#tk-status-OPEN");
        plain.click("#tk-filters-apply");
        plain.waitForLoadState();
        assertThat(plain.url()).contains("status=OPEN", "status=CLOSED").doesNotContain("filters=1");
        assertThat(plain.isVisible("#tk-status-OPEN")).isFalse();
        plain.context().close();
    }

    private static CaseFields fields(CaseSeed seed, long team, long owner, String title, Sensitivity sensitivity) {
        return fields(seed, team, owner, title, sensitivity, CasePriority.NORMAL);
    }

    private static CaseFields fields(
            CaseSeed seed, long team, long owner, String title, Sensitivity sensitivity, CasePriority priority) {
        return new CaseFields(
                seed.caseType(),
                title,
                priority,
                LocalDate.now(ZoneOffset.UTC).minusDays(2),
                team,
                owner,
                "Synthetic subject",
                "Synthetic description",
                sensitivity);
    }
}
