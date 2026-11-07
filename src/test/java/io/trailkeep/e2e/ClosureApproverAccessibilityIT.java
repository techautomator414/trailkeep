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
import java.util.ArrayList;
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
 * Reassign approver and the dashboard's "Pending my approval" (WP-19 part c2b) in a real browser after real OIDC
 * logins: {@code alpha.analyst} owns the case and requested closure; {@code alpha.manager1} acts, from the keyboard.
 * Reassignment: Enter on "Reassign approver" opens the dialog on the approver combobox; typing narrows it, Down and
 * Enter choose the substitute Manager, Tab and Enter send; the banner names the new approver. Dashboard: as the
 * approver, Tab reaches the request's Approve closure and Enter closes the case. axe finds 0 violations with the
 * dialog open and the list expanded, after the reassignment, on the fallback page and on the dashboard.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class ClosureApproverAccessibilityIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String ALPHA_ANALYST = "00000000-0000-4000-8000-000000000002";
    private static final String ALPHA_MANAGER = "00000000-0000-4000-8000-000000000003";
    private static final String TITLE = "Synthetic approver golden path";

    private static Playwright playwright;
    private static Browser browser;

    private final List<String> consoleProblems = new CopyOnWriteArrayList<>();
    private final List<Long> seededUsers = new ArrayList<>();
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
        seededUsers.forEach(user -> jdbc.update("DELETE FROM APP_USER WHERE ID = ?", user));
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE_OUTCOME WHERE CASE_TYPE_ID = ?", caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    @Test
    void managerReassignsTheApproverFromTheKeyboard() {
        final long analyst = signIn("alpha.analyst", ALPHA_ANALYST);
        final long manager = signIn("alpha.manager1", ALPHA_MANAGER);
        CaseSeed seed = seedTeam(analyst, manager);
        final long approver = manager(seed);
        final long substitute = manager(seed);
        CaseNumber number = pendingCase(analyst, approver);

        page.navigate(KEYCLOAK.app("/cases/" + number));
        assertClean(TITLE);
        page.focus("a[data-tk-dialog-open='tk-approver-dialog']");
        page.keyboard().press("Enter");
        assertThat(page.evaluate("() => document.activeElement.id")).isEqualTo("tk-new-approver-input");
        page.keyboard().type(String.valueOf(substitute));
        page.keyboard().press("ArrowDown");
        assertThat(page.getAttribute("#tk-new-approver-input", "aria-expanded")).isEqualTo("true");
        assertClean(TITLE);
        page.keyboard().press("Enter");
        assertThat(page.inputValue("#tk-new-approver-input")).isEqualTo("Seed User " + substitute);
        page.keyboard().press("Tab");
        page.keyboard().press("Enter");
        page.waitForSelector("text=The approver was changed.");
        assertClean(TITLE);

        assertThat(page.textContent("#tk-pending-text")).contains("Approver: Seed User " + substitute + ".");
    }

    @Test
    void theFallbackPageIsCleanToo() {
        final long analyst = signIn("alpha.analyst", ALPHA_ANALYST);
        final long manager = signIn("alpha.manager1", ALPHA_MANAGER);
        CaseSeed seed = seedTeam(analyst, manager);
        manager(seed);
        CaseNumber number = pendingCase(analyst, manager(seed));
        page.navigate(KEYCLOAK.app("/cases/" + number));
        String form = Objects.requireNonNull(
                page.getAttribute("a[data-tk-dialog-open='tk-approver-dialog']", "href"),
                "the case page offers the reassignment");
        page.navigate(KEYCLOAK.app(form));
        assertClean("Reassign the approver of " + number);
    }

    @Test
    void approverApprovesFromTheDashboard() {
        final long analyst = signIn("alpha.analyst", ALPHA_ANALYST);
        final long manager = signIn("alpha.manager1", ALPHA_MANAGER);
        seedTeam(analyst, manager);
        CaseNumber number = pendingCase(analyst, manager);

        page.navigate(KEYCLOAK.app("/"));
        assertClean("Dashboard");
        assertThat(page.textContent("main")).contains("Pending my approval", "requested closure of " + number);
        page.focus("main a[href$='/decision']");
        page.keyboard().press("Shift+Tab");
        assertThat(page.evaluate("() => document.activeElement.textContent.trim()"))
                .isEqualTo("Approve closure");
        page.keyboard().press("Enter");
        page.waitForSelector("text=Closure was approved and the case is closed.");
        assertClean(TITLE);
    }

    /** The team, with the analyst and the manager in it and the case type offering "Resolved". */
    private CaseSeed seedTeam(long analyst, long manager) {
        CaseSeed seed = new CaseSeed(jdbc);
        team = seed.team();
        caseType = seed.caseType();
        seed.member(analyst, team, TeamRole.ANALYST);
        seed.member(manager, team, TeamRole.MANAGER);
        jdbc.update(
                "INSERT INTO CASE_TYPE_OUTCOME (CASE_TYPE_ID, OUTCOME, SORT_ORDER) VALUES (?, 'Resolved', 1)",
                caseType);
        return seed;
    }

    /** One more Manager of the team, a seeded user ("Seed User id"). */
    private long manager(CaseSeed seed) {
        long id = seed.user();
        seededUsers.add(id);
        seed.member(id, team, TeamRole.MANAGER);
        return id;
    }

    /** A case owned by the analyst, its closure requested from {@code approver}. */
    private CaseNumber pendingCase(long analyst, long approver) {
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
        closures.requestClosure(owner, number.toString(), "Resolved", "Synthetic summary", null, approver);
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
