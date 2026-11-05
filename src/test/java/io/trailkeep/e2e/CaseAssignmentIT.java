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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
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
 * Reassign and Mark restricted / standard in a real browser (WP-12, BUILD_PLAN §4.3, §10.1). The dev realm's {@code
 * alpha.analyst} opens Mark restricted from the keyboard, closes it with Escape (focus returns to the opener), then
 * restricts; opens Reassign, picks an owner outside the chosen team (422), loses a race to a Manager's edit (409),
 * reloads and reassigns. {@code alpha.manager1} opens Mark standard, loses a race (409), reloads and confirms. Every
 * state (case page, each dialog, each error page, each notice) has 0 axe violations and no CSP violation in the
 * console. The rows the test commits are removed afterwards. Browser handling follows {@link ShellAccessibilityIT}.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class CaseAssignmentIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String ALPHA_ANALYST = "00000000-0000-4000-8000-000000000002";
    private static final String ALPHA_MANAGER = "00000000-0000-4000-8000-000000000003";

    private static Playwright playwright;
    private static Browser browser;

    private final List<String> consoleProblems = new CopyOnWriteArrayList<>();
    private final List<Long> teams = new ArrayList<>();
    private final List<Long> users = new ArrayList<>();
    private Page page;
    private @Nullable CaseSeed seed;

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

    @AfterEach
    void removeCommittedRows() {
        // Test-only cleanup as the schema owner; the application user has no DELETE grant on AUDIT_EVENT.
        String ofTeam = " WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = ?)";
        for (long team : teams) {
            jdbc.update("DELETE FROM AUDIT_EVENT" + ofTeam, team);
            jdbc.update("DELETE FROM CASE_SEARCH" + ofTeam, team);
            jdbc.update("DELETE FROM CASE_SLA" + ofTeam, team);
            jdbc.update("DELETE FROM CASE_RECORD WHERE TEAM_ID = ?", team);
            jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", team);
            jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        }
        users.forEach(user -> jdbc.update("DELETE FROM APP_USER WHERE ID = ?", user));
        if (seed != null) {
            long type = seed.caseType();
            Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, type);
            jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", type);
            jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
        }
    }

    @Test
    void anAnalystRestrictsThenReassignsThroughDialogsAndErrorPages() {
        long analyst = signIn("alpha.analyst", ALPHA_ANALYST);
        CaseSeed seed = seed();
        long team = team(seed);
        long secondTeam = team(seed);
        seed.member(analyst, team, TeamRole.ANALYST);
        seed.member(analyst, secondTeam, TeamRole.ANALYST);
        long manager = user(seed);
        seed.member(manager, team, TeamRole.MANAGER);
        long outsider = user(seed);
        seed.member(outsider, secondTeam, TeamRole.ANALYST);
        CaseNumber number = cases.create(actor(analyst, team), fields(seed, team, analyst, Sensitivity.STANDARD));
        open(number);

        String restrictOpener = "a[data-tk-dialog-open='tk-RESTRICTED-dialog']";
        page.focus(restrictOpener);
        page.keyboard().press("Enter");
        assertDialogClean("#tk-RESTRICTED-dialog", "Synthetic assign case");
        page.keyboard().press("Escape");
        assertThat(page.isVisible("#tk-RESTRICTED-dialog")).isFalse();
        assertThat(page.evaluate("s => document.activeElement === document.querySelector(s)", restrictOpener))
                .isEqualTo(true);
        page.keyboard().press("Enter");
        page.waitForSelector("#tk-RESTRICTED-dialog[open]");
        page.click("#tk-RESTRICTED-dialog button[type=submit]");
        page.waitForSelector("[data-tk-toast]");
        assertClean("Synthetic assign case");
        assertThat(page.textContent("[data-tk-toast]")).contains("The case is now restricted.");

        page.click("a[data-tk-dialog-open='tk-reassign-dialog']");
        assertDialogClean("#tk-reassign-dialog", "Synthetic assign case");
        page.selectOption("#tk-reassign-dialog #teamId", Long.toString(team));
        page.selectOption("#tk-reassign-dialog #ownerId", Long.toString(outsider));
        assertThat(submit("#tk-reassign-dialog").status()).isEqualTo(422);
        assertErrorPageClean("Reassign " + number);

        cases.edit(
                actor(manager, team),
                number.toString(),
                1,
                new CaseChanges("Manager title", CasePriority.HIGH, null, "Manager description"));
        page.selectOption("#teamId", Long.toString(secondTeam));
        page.selectOption("#ownerId", Long.toString(analyst));
        assertThat(submit("main").status()).isEqualTo(409);
        assertErrorPageClean("Reassign " + number);

        page.click("[data-tk-error-summary] a");
        page.waitForSelector("main button[type=submit]");
        assertClean("Reassign " + number);
        page.selectOption("#teamId", Long.toString(secondTeam));
        page.selectOption("#ownerId", Long.toString(analyst));
        submit("main");
        page.waitForURL(url -> url.endsWith("/cases/" + number));
        assertClean("Manager title");
        assertThat(page.textContent("[data-tk-toast]")).contains("The case was reassigned.");
    }

    @Test
    void managersMarkARestrictedCaseStandardAfterLosingARace() {
        long manager = signIn("alpha.manager1", ALPHA_MANAGER);
        CaseSeed seed = seed();
        long team = team(seed);
        seed.member(manager, team, TeamRole.MANAGER);
        long owner = user(seed);
        seed.member(owner, team, TeamRole.ANALYST);
        CaseNumber number = cases.create(actor(owner, team), fields(seed, team, owner, Sensitivity.RESTRICTED));
        open(number);

        page.click("a[data-tk-dialog-open='tk-STANDARD-dialog']");
        assertDialogClean("#tk-STANDARD-dialog", "Synthetic assign case");
        cases.edit(
                actor(owner, team),
                number.toString(),
                0,
                new CaseChanges("Owner title", CasePriority.HIGH, null, "Owner description"));
        assertThat(submit("#tk-STANDARD-dialog").status()).isEqualTo(409);
        assertErrorPageClean("Mark " + number + " standard");

        page.click("[data-tk-error-summary] a");
        page.waitForURL(url -> url.endsWith("/cases/" + number));
        page.click("a[data-tk-dialog-open='tk-STANDARD-dialog']");
        page.waitForSelector("#tk-STANDARD-dialog[open]");
        submit("#tk-STANDARD-dialog");
        page.waitForSelector("[data-tk-toast]");
        assertClean("Owner title");
        assertThat(page.textContent("[data-tk-toast]")).contains("The case is now standard.");
    }

    private long signIn(String username, String externalId) {
        page.navigate(KEYCLOAK.app("/"));
        new SignInPage(page).signIn(username, KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        page.waitForLoadState();
        consoleProblems.clear();
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, externalId));
    }

    private void open(CaseNumber number) {
        Response casePage = Objects.requireNonNull(page.navigate(KEYCLOAK.app("/cases/" + number)));
        assertThat(casePage.status()).isEqualTo(200);
        assertClean("Synthetic assign case");
    }

    private Response submit(String scope) {
        return page.waitForResponse(
                response -> response.request().method().equals("POST"),
                () -> page.click(scope + " form button[type=submit]"));
    }

    private void assertDialogClean(String dialog, String heading) {
        page.waitForSelector(dialog + "[open]");
        assertThat(page.evaluate("s => document.querySelector(s).contains(document.activeElement)", dialog))
                .isEqualTo(true);
        assertClean(heading);
    }

    private void assertErrorPageClean(String heading) {
        page.waitForSelector("[data-tk-error-summary]");
        assertThat(page.evaluate("() => document.activeElement.hasAttribute('data-tk-error-summary')"))
                .isEqualTo(true);
        assertClean(heading);
    }

    private void assertClean(String heading) {
        page.waitForLoadState();
        ShellPage shell = new ShellPage(page);
        assertThat(shell.heading()).contains(heading);
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }

    private CaseSeed seed() {
        CaseSeed created = new CaseSeed(jdbc);
        seed = created;
        return created;
    }

    private long team(CaseSeed seed) {
        long team = seed.team();
        teams.add(team);
        return team;
    }

    private long user(CaseSeed seed) {
        long user = seed.user();
        users.add(user);
        return user;
    }

    private static CaseService.Actor actor(long userId, long team) {
        return new CaseService.Actor(
                new UserPermissions(userId, false, Set.of(), Map.of(team, TeamRole.MANAGER)), "Synthetic Actor");
    }

    private static CaseFields fields(CaseSeed seed, long team, long owner, Sensitivity sensitivity) {
        return new CaseFields(
                seed.caseType(),
                "Synthetic assign case",
                CasePriority.NORMAL,
                LocalDate.now(ZoneOffset.UTC).minusDays(2),
                team,
                owner,
                "Synthetic subject",
                "Synthetic description",
                sensitivity);
    }
}
