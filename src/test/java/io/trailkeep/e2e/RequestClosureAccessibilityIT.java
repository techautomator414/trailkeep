// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
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
 * Golden path 2 up to "pending" (WP-19 part c1) in a real browser after a real OIDC login as the dev realm's
 * {@code alpha.analyst}, the case's owner, driven from the keyboard: Enter on "Request closure" opens the dialog on
 * the outcome select; Down picks the outcome, Tab and typing fill the summary (its count follows), Tab skips the
 * optional follow-ups, Tab reaches the approver combobox, typing narrows it, Down and Enter choose the Manager, Tab and
 * Enter send. The case page comes back with the toast and the pending-closure banner, and no longer offers Request
 * closure or Edit. axe finds 0 violations with the dialog closed, open with the list expanded, and after the request.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class RequestClosureAccessibilityIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String ALPHA_ANALYST = "00000000-0000-4000-8000-000000000002";
    private static final String TITLE = "Synthetic closure golden path";
    private static final String SUMMARY = "Synthetic summary of the remediation";

    private static Playwright playwright;
    private static Browser browser;

    private final List<String> consoleProblems = new CopyOnWriteArrayList<>();
    private Page page;
    private long team;
    private long caseType;
    private long manager;

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
        if (team == 0) {
            return;
        }
        String ofTeam = " WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = ?)";
        for (String table : new String[] {
            "CLOSURE_REQUEST", "NOTIFICATION_OUTBOX", "USER_ALERT", "AUDIT_EVENT", "CASE_SEARCH", "CASE_SLA"
        }) {
            jdbc.update("DELETE FROM " + table + ofTeam, team);
        }
        jdbc.update("DELETE FROM CASE_RECORD WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM APP_USER WHERE ID = ?", manager);
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE_OUTCOME WHERE CASE_TYPE_ID = ?", caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    @Test
    void ownerRequestsClosureFromTheKeyboardAndTheCaseIsPending() {
        page.navigate(KEYCLOAK.app("/"));
        new SignInPage(page).signIn("alpha.analyst", KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        page.waitForLoadState();
        consoleProblems.clear();
        CaseNumber number = seedOwnedCase();

        page.navigate(KEYCLOAK.app("/cases/" + number));
        assertClean();
        page.focus("a[data-tk-dialog-open='tk-closure-dialog']");
        page.keyboard().press("Enter");
        assertThat(page.evaluate("() => document.activeElement.id")).isEqualTo("tk-outcome");
        page.keyboard().press("ArrowDown");
        assertThat(page.inputValue("#tk-outcome")).isEqualTo("Resolved");
        page.keyboard().press("Tab");
        assertThat(page.evaluate("() => document.activeElement.id")).isEqualTo("tk-summary");
        page.keyboard().type(SUMMARY);
        assertThat(page.locator("#tk-summary + .form-text").textContent())
                .isEqualTo(SUMMARY.length() + " of 5000 characters");
        page.keyboard().press("Tab");
        assertThat(page.evaluate("() => document.activeElement.id")).isEqualTo("tk-follow-ups");
        page.keyboard().press("Tab");
        assertThat(page.evaluate("() => document.activeElement.id")).isEqualTo("tk-approver-input");
        page.keyboard().type(String.valueOf(manager));
        page.keyboard().press("ArrowDown");
        assertThat(page.getAttribute("#tk-approver-input", "aria-expanded")).isEqualTo("true");
        assertClean();
        page.keyboard().press("Enter");
        assertThat(page.inputValue("#tk-approver-input")).isEqualTo("Seed User " + manager);
        page.keyboard().press("Tab");
        page.keyboard().press("Enter");
        page.waitForSelector("text=Closure was requested.");
        assertClean();

        assertThat(page.textContent("#tk-pending-title")).isEqualTo("Pending closure");
        assertThat(page.textContent("main")).contains(SUMMARY, "Approver: Seed User " + manager);
        assertThat(page.querySelector("a[data-tk-dialog-open='tk-closure-dialog']"))
                .isNull();
        assertThat(page.querySelector("main a[href$='/edit']")).isNull();
    }

    /** A case owned by the signed-in analyst in a team with one Manager, its type offering "Resolved". */
    private CaseNumber seedOwnedCase() {
        long analyst = Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, ALPHA_ANALYST));
        CaseSeed seed = new CaseSeed(jdbc);
        team = seed.team();
        caseType = seed.caseType();
        seed.member(analyst, team, TeamRole.ANALYST);
        manager = seed.user();
        seed.member(manager, team, TeamRole.MANAGER);
        jdbc.update(
                "INSERT INTO CASE_TYPE_OUTCOME (CASE_TYPE_ID, OUTCOME, SORT_ORDER) VALUES (?, 'Resolved', 1)",
                caseType);
        return cases.create(
                new CaseService.Actor(
                        new UserPermissions(analyst, false, Set.of(), Map.of(team, TeamRole.ANALYST)),
                        "Synthetic Analyst"),
                new CaseFields(
                        caseType,
                        TITLE,
                        CasePriority.NORMAL,
                        LocalDate.now(ZoneOffset.UTC).minusDays(2),
                        team,
                        analyst,
                        "Synthetic subject",
                        "Synthetic description",
                        Sensitivity.STANDARD));
    }

    private void assertClean() {
        page.waitForLoadState();
        ShellPage shell = new ShellPage(page);
        assertThat(shell.heading()).contains(TITLE);
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }
}
