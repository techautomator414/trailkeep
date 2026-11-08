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
import io.trailkeep.casefile.CaseService.Actor;
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
 * Legal hold (WP-24 part b) in a real browser after a real OIDC login as the dev realm's {@code compliance.admin}, from
 * the keyboard: on a case's page, Enter on "Place legal hold" opens the dialog with the focus in the reason, typing a
 * reason and Tab, Enter places the hold, and the page comes back with the notice, the "Legal hold" chip, who, when and
 * why, and "Lift legal hold". The fallback page for lifting is clean, and a blank reason sent from it comes back as a
 * 422 with the error summary focused. axe finds 0 violations on each.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class LegalHoldAccessibilityIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String TITLE = "Synthetic held golden path";
    private static final String REASON = "Synthetic reason: a preservation notice";

    private static Playwright playwright;
    private static Browser browser;

    private final List<String> consoleProblems = new CopyOnWriteArrayList<>();
    private Page page;
    private long team;
    private long analyst;
    private long caseType;

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

    @AfterEach
    void removeCommittedRows() {
        page.context().close();
        if (team == 0) {
            return;
        }
        // Test-only cleanup as the schema owner; the application user has no DELETE grant on AUDIT_EVENT.
        String ofTeam = " WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = ?)";
        for (String table : new String[] {"LEGAL_HOLD_EVENT", "AUDIT_EVENT", "CASE_SEARCH", "CASE_SLA"}) {
            jdbc.update("DELETE FROM " + table + ofTeam, team);
        }
        jdbc.update("DELETE FROM CASE_RECORD WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM APP_USER WHERE ID = ?", analyst);
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    @Test
    void complianceAdminsPlaceAHoldFromTheKeyboard() {
        CaseNumber number = openCase();
        page.navigate(KEYCLOAK.app("/cases/" + number));
        assertClean(TITLE);
        page.focus("a[data-tk-dialog-open='tk-hold-dialog']");
        page.keyboard().press("Enter");
        assertThat(page.evaluate("() => document.activeElement.id")).isEqualTo("tk-hold-reason");
        page.keyboard().type(REASON);
        assertClean(TITLE);
        page.keyboard().press("Tab");
        page.keyboard().press("Enter");
        page.waitForSelector("text=The case is on legal hold.");
        assertClean(TITLE);

        assertThat(page.textContent(".tk-chips")).contains("Legal hold");
        assertThat(page.textContent("section[aria-labelledby='tk-hold-title']"))
                .contains("On legal hold since ", REASON);
        assertThat(page.textContent("a[data-tk-dialog-open='tk-hold-dialog']")).isEqualTo("Lift legal hold");
    }

    @Test
    void theFallbackPageAndItsRefusalAreClean() {
        CaseNumber number = openCase();
        page.navigate(KEYCLOAK.app("/cases/" + number + "/legal-hold"));
        assertClean("Place a legal hold on " + number);
        page.fill("#tk-hold-reason", "   ");
        Response refused = page.waitForResponse(
                response -> response.request().method().equals("POST"), () -> page.click("main button[type=submit]"));
        assertThat(refused.status()).isEqualTo(422);
        assertClean("Place a legal hold on " + number);
        assertThat(page.evaluate("() => document.activeElement.getAttribute('aria-labelledby')"))
                .isEqualTo("tk-hold-errors");
    }

    /** An open case of a seeded team, owned by a seeded Analyst; the Compliance Admin reads every team. */
    private CaseNumber openCase() {
        CaseSeed seed = new CaseSeed(jdbc);
        team = seed.team();
        caseType = seed.caseType();
        analyst = seed.user();
        seed.member(analyst, team, TeamRole.ANALYST);
        Actor owner = new Actor(
                new UserPermissions(analyst, false, Set.of(), Map.of(team, TeamRole.ANALYST)), "Synthetic Analyst");
        return cases.create(
                owner,
                new CaseFields(
                        caseType,
                        TITLE,
                        CasePriority.NORMAL,
                        LocalDate.now(ZoneOffset.UTC),
                        team,
                        analyst,
                        null,
                        "Synthetic description",
                        Sensitivity.STANDARD));
    }

    private void assertClean(String heading) {
        page.waitForLoadState();
        ShellPage shell = new ShellPage(page);
        assertThat(shell.heading()).contains(heading);
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }
}
