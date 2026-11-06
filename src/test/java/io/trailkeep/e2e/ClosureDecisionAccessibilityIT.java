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
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.closure.ClosureService;
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
 * Golden paths 2 and 3 from "pending" (WP-19 part c2; RequestClosureAccessibilityIT walks the request itself) in a
 * real browser after real OIDC logins: {@code alpha.analyst} owns the case and requested closure from
 * {@code alpha.manager1}, who decides from the keyboard. Path 2: Tab reaches Approve closure, Enter closes the case,
 * which comes back with the toast and its closure shown, locked. Path 3: Enter on "Reject or request changes" opens
 * the dialog on the first decision, Space chooses Reject, Tab and typing fill the reason (its count follows), Tab and
 * Enter send it; the case is open again with the toast, the "Requires Changes" badge and the reason. axe finds 0
 * violations on the pending case, with the dialog open, on the fallback page and after each decision.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class ClosureDecisionAccessibilityIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String ALPHA_ANALYST = "00000000-0000-4000-8000-000000000002";
    private static final String ALPHA_MANAGER = "00000000-0000-4000-8000-000000000003";
    private static final String TITLE = "Synthetic decision golden path";
    private static final String SUMMARY = "Synthetic summary of the remediation";
    private static final String REASON = "Synthetic reason: the evidence is missing";

    private static Playwright playwright;
    private static Browser browser;

    private final List<String> consoleProblems = new CopyOnWriteArrayList<>();
    private Page page;
    private long team;
    private long caseType;

    @Autowired
    private CaseService cases;

    @Autowired
    private ClosureService closures;

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
        page = newPage();
    }

    @AfterEach
    void removeCommittedRows() {
        page.context().close();
        if (team == 0) {
            return;
        }
        // Test-only cleanup as the schema owner; the application user has no DELETE grant on AUDIT_EVENT.
        String ofTeam = " WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = ?)";
        for (String table : new String[] {
            "CLOSURE_REQUEST", "NOTIFICATION_OUTBOX", "USER_ALERT", "AUDIT_EVENT", "CASE_SEARCH", "CASE_SLA"
        }) {
            jdbc.update("DELETE FROM " + table + ofTeam, team);
        }
        jdbc.update("DELETE FROM CASE_RECORD WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE_OUTCOME WHERE CASE_TYPE_ID = ?", caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    @Test
    void goldenPathTwoTheApproverApprovesFromTheKeyboard() {
        CaseNumber number = pendingCase();

        page.navigate(KEYCLOAK.app("/cases/" + number));
        assertClean(TITLE);
        page.focus("a[data-tk-dialog-open='tk-decision-dialog']");
        page.keyboard().press("Shift+Tab");
        assertThat(page.evaluate("() => document.activeElement.textContent.trim()"))
                .isEqualTo("Approve closure");
        page.keyboard().press("Enter");
        page.waitForSelector("text=Closure was approved and the case is closed.");
        assertClean(TITLE);

        assertThat(page.textContent("#tk-decided-title")).isEqualTo("Closure");
        assertThat(page.textContent("main"))
                .contains("The closure fields are locked.", SUMMARY)
                .doesNotContain("Pending closure", "Reject or request changes");
    }

    @Test
    void goldenPathThreeTheApproverRejectsWithAReason() {
        CaseNumber number = pendingCase();

        page.navigate(KEYCLOAK.app("/cases/" + number));
        page.focus("a[data-tk-dialog-open='tk-decision-dialog']");
        page.keyboard().press("Enter");
        assertThat(page.evaluate("() => document.activeElement.id")).isEqualTo("tk-decision-REJECTED");
        page.keyboard().press("Space");
        assertThat(page.isChecked("#tk-decision-REJECTED")).isTrue();
        page.keyboard().press("Tab");
        assertThat(page.evaluate("() => document.activeElement.id")).isEqualTo("tk-reason");
        page.keyboard().type(REASON);
        assertThat(page.locator("#tk-reason + .form-text").textContent())
                .isEqualTo(REASON.length() + " of 1000 characters");
        assertClean(TITLE);
        page.keyboard().press("Tab");
        page.keyboard().press("Enter");
        page.waitForSelector("text=Closure was rejected and the case is open again.");
        assertClean(TITLE);

        assertThat(page.textContent(".tk-chips")).contains("Requires Changes");
        assertThat(page.textContent("#tk-decided-title")).isEqualTo("Closure rejected");
        assertThat(page.textContent("main")).contains(REASON, SUMMARY).doesNotContain("Pending closure");
    }

    @Test
    void theFallbackPageIsCleanToo() {
        CaseNumber number = pendingCase();
        String request = Objects.requireNonNull(
                page.getAttribute("a[data-tk-dialog-open='tk-decision-dialog']", "href"),
                "the case page offers the decision");
        page.navigate(KEYCLOAK.app(request));
        assertClean("Reject or request changes on " + number);
        assertThat(page.textContent("main")).contains(SUMMARY);
    }

    /**
     * Signs both people in (each in their own browser context, so each has an APP_USER row), seeds a case owned by
     * the analyst in a team where the manager is the one other Manager, requests its closure from the manager as the
     * analyst, and leaves the manager on that case's page.
     */
    private CaseNumber pendingCase() {
        long analyst = signIn("alpha.analyst", ALPHA_ANALYST);
        long manager = signIn("alpha.manager1", ALPHA_MANAGER);
        CaseSeed seed = new CaseSeed(jdbc);
        team = seed.team();
        caseType = seed.caseType();
        seed.member(analyst, team, TeamRole.ANALYST);
        seed.member(manager, team, TeamRole.MANAGER);
        jdbc.update(
                "INSERT INTO CASE_TYPE_OUTCOME (CASE_TYPE_ID, OUTCOME, SORT_ORDER) VALUES (?, 'Resolved', 1)",
                caseType);
        Actor owner = new Actor(
                new UserPermissions(analyst, false, Set.of(), Map.of(team, TeamRole.ANALYST)), "Synthetic Analyst");
        CaseNumber number = cases.create(
                owner,
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
        closures.requestClosure(owner, number.toString(), "Resolved", SUMMARY, null, manager);
        page.navigate(KEYCLOAK.app("/cases/" + number));
        page.waitForLoadState();
        return number;
    }

    private long signIn(String username, String externalId) {
        page.context().close();
        page = newPage();
        page.navigate(KEYCLOAK.app("/"));
        new SignInPage(page).signIn(username, KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        page.waitForLoadState();
        consoleProblems.clear();
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, externalId));
    }

    /** A page in a fresh browser context (its own cookies, so its own login), its console watched. */
    private Page newPage() {
        Page fresh = browser.newContext().newPage();
        fresh.onConsoleMessage(message -> {
            if (message.text().contains("Content Security Policy")
                    || message.text().contains("Refused to")) {
                consoleProblems.add(message.text());
            }
        });
        fresh.onPageError(error -> consoleProblems.add(error));
        return fresh;
    }

    private void assertClean(String heading) {
        page.waitForLoadState();
        ShellPage shell = new ShellPage(page);
        assertThat(shell.heading()).contains(heading);
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }
}
