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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 * The report schedules (WP-23 part b2) in a real browser after a real OIDC login as the dev realm's {@code
 * compliance.admin}, driven from the keyboard: from SLA policies, Tab and Enter on "Report schedules" open the list;
 * Tab and Enter on a team open its form; Tab moves through the checkbox, the day and the hour (each changed with the
 * arrow keys) to Save, and Enter saves back to the list, where the team now reads "Enabled" on the chosen day and
 * hour. A form whose schedule changed meanwhile comes back as a 409 with the conflict panel focused and Reload, which
 * Enter follows. axe finds 0 violations on the list, the form and the conflict.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class ReportSchedulesAccessibilityIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final long TEAM = 984_921L;
    private static final String TEAM_NAME = "Aardvark Keyboard Team";

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
    void signInAsComplianceAdmin() {
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, ?)", TEAM, TEAM_NAME);
        page = browser.newContext().newPage();
        page.onConsoleMessage(message -> {
            if (message.text().contains("Content Security Policy")
                    || message.text().contains("Refused to")) {
                consoleProblems.add(message.text());
            }
        });
        page.onPageError(error -> consoleProblems.add(error));
        page.navigate(KEYCLOAK.app("/admin/sla-policies"));
        new SignInPage(page).signIn("compliance.admin", KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        page.waitForLoadState();
        consoleProblems.clear();
    }

    @AfterEach
    void removeTheTeam() {
        page.context().close();
        // Test-only cleanup of the committed fixture, as the schema owner.
        jdbc.update("DELETE FROM REPORT_SCHEDULE WHERE TEAM_ID = ?", TEAM);
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", TEAM);
    }

    @Test
    void schedulesAreReachedChangedAndSavedFromTheKeyboard() {
        page.locator("#main").focus();
        tabTo("Report schedules", 3);
        page.keyboard().press("Enter");
        page.waitForURL(url -> url.endsWith("/admin/report-schedules"));
        assertClean("Report schedules");

        page.locator("#main").focus();
        tabTo(TEAM_NAME, 6);
        page.keyboard().press("Enter");
        page.waitForURL(url -> url.endsWith("/admin/report-schedules/" + TEAM));
        assertClean("Aging report for " + TEAM_NAME);
        assertThat(page.isChecked("#enabled")).isTrue();

        page.locator("#main").focus();
        tabTo("All report schedules", 1);
        tabToId("enabled", 1);
        tabToId("weekday", 1);
        page.keyboard().press("ArrowDown");
        tabToId("hour", 1);
        page.keyboard().press("ArrowDown");
        page.keyboard().press("ArrowDown");
        assertThat(page.inputValue("#weekday")).isEqualTo("TUESDAY");
        assertThat(page.inputValue("#hour")).isEqualTo("8");
        tabTo("Save schedule", 1);
        page.keyboard().press("Enter");
        page.waitForURL(url -> url.endsWith("/admin/report-schedules"));
        assertClean("Report schedules");
        assertThat(page.locator("tr", new Page.LocatorOptions().setHasText(TEAM_NAME))
                        .textContent())
                .contains("Enabled", "Tuesdays at 08:00");
    }

    @Test
    void staleFormsComeBackAsConflictsWithReloadFocusable() {
        page.navigate(KEYCLOAK.app("/admin/report-schedules/" + TEAM));
        assertClean("Aging report for " + TEAM_NAME);
        jdbc.update(
                "INSERT INTO REPORT_SCHEDULE (ID, TEAM_ID, RUN_WEEKDAY, RUN_HOUR, NEXT_RUN_AT)"
                        + " VALUES (?, ?, 3, 10, ?)",
                TEAM,
                TEAM,
                Timestamp.from(Instant.now().plus(Duration.ofDays(3))));

        page.locator("#main").focus();
        tabTo("All report schedules", 1);
        tabToId("enabled", 1);
        page.keyboard().press("Space");
        Response conflict =
                page.waitForResponse(response -> response.request().method().equals("POST"), () -> {
                    tabTo("Save schedule", 3);
                    page.keyboard().press("Enter");
                });
        assertThat(conflict.status()).isEqualTo(409);
        assertClean("Aging report for " + TEAM_NAME);
        assertThat(page.evaluate("() => document.activeElement.getAttribute('aria-labelledby')"))
                .isEqualTo("tk-conflict-title");
        assertThat(page.isChecked("#enabled")).isFalse();
        assertThat(page.locator("main button[type=submit]").count()).isZero();

        tabTo("Reload", 1);
        page.keyboard().press("Enter");
        page.waitForLoadState();
        assertClean("Aging report for " + TEAM_NAME);
        assertThat(page.inputValue("#weekday")).isEqualTo("WEDNESDAY");
        assertThat(page.inputValue("#hour")).isEqualTo("10");
    }

    private void assertClean(String heading) {
        page.waitForLoadState();
        ShellPage shell = new ShellPage(page);
        assertThat(shell.heading()).isEqualTo(heading);
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }

    /** Presses Tab until the element with the given id has the focus. */
    private void tabToId(String id, int presses) {
        for (int i = 1; i <= presses; i++) {
            page.keyboard().press("Tab");
            if (id.equals(page.evaluate("() => document.activeElement.id"))) {
                return;
            }
        }
        throw new AssertionError("Tab never reached #" + id);
    }

    /** Presses Tab until the focused element's accessible name (its aria-label, or its text) is the given one. */
    private void tabTo(String name, int presses) {
        for (int i = 1; i <= presses; i++) {
            page.keyboard().press("Tab");
            Object focused = page.evaluate("() => (document.activeElement.getAttribute('aria-label')"
                    + " || document.activeElement.textContent).replace(/\\s+/g, ' ').trim()");
            if (name.equals(focused)) {
                return;
            }
        }
        throw new AssertionError("Tab never reached " + name);
    }
}
