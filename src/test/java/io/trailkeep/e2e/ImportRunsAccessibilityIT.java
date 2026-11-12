// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import io.trailkeep.e2e.pages.ShellPage;
import io.trailkeep.e2e.pages.SignInPage;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.KeycloakDevRealm;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * The Import runs screen (WP-26 part s1) in a real browser after a real OIDC login as the dev realm's {@code
 * compliance.admin}, driven from the keyboard: from the System screen, Tab and Enter on "All import runs" open the
 * list, which says there are none (the test first removes any run left in the shared database); with two runs, Tab
 * and Enter on "Open import run" open the latest, which has no dry run yet, Tab and Enter on "All import runs" go back
 * to the list, and Tab and Enter on the other run open it with its issues and figures. axe finds 0 violations on the
 * empty list, the list, and both run pages, with no CSP problem.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class ImportRunsAccessibilityIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final long DRY_RUN = 986_501L;
    private static final long VALIDATED = 986_502L;

    private static Playwright playwright;
    private static Browser browser;

    private final List<String> consoleProblems = new CopyOnWriteArrayList<>();
    private Page page;
    private long requester;

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
        requester = new CaseSeed(jdbc).user();
        page = browser.newContext().newPage();
        page.onConsoleMessage(message -> {
            if (message.text().contains("Content Security Policy")
                    || message.text().contains("Refused to")) {
                consoleProblems.add(message.text());
            }
        });
        page.onPageError(error -> consoleProblems.add(error));
        page.navigate(KEYCLOAK.app("/admin/system"));
        new SignInPage(page).signIn("compliance.admin", KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        page.waitForLoadState();
        consoleProblems.clear();
    }

    @AfterEach
    void removeTheRuns() {
        page.context().close();
        // Test-only cleanup of the committed fixture, as the schema owner, children first.
        for (String table : List.of("IMPORT_ISSUE", "IMPORT_RUN_SUMMARY")) {
            jdbc.update("DELETE FROM " + table + " WHERE RUN_ID IN (?, ?)", DRY_RUN, VALIDATED);
        }
        jdbc.update("DELETE FROM IMPORT_RUN WHERE ID IN (?, ?)", DRY_RUN, VALIDATED);
        jdbc.update("DELETE FROM APP_USER WHERE ID = ?", requester);
    }

    @Test
    void importRunsAreReachedAndReadFromTheKeyboard() {
        // The empty state needs no runs at all: remove anything another test left in the shared database, children
        // first (test-only cleanup over the fixed table names of this list).
        for (String table : List.of(
                "CASE_IMPORTED_EVENT",
                "IMPORT_STG_EVENT",
                "IMPORT_STG_ATTACHMENT",
                "IMPORT_STG_COMMENT",
                "IMPORT_STG_CASE",
                "IMPORT_STG_PERSON",
                "IMPORT_RUN_CHECKPOINT",
                "IMPORT_ISSUE",
                "IMPORT_RUN_SUMMARY",
                "IMPORT_RUN")) {
            jdbc.update("DELETE FROM " + table);
        }
        follow("/admin/system", "All import runs", "/admin/import-runs");
        assertClean("Import runs");
        assertThat(page.textContent("main")).contains("No import has been run.");

        seed();
        follow("/admin/system", "Open import run " + VALIDATED, "/admin/import-runs/" + VALIDATED);
        assertClean("Import run " + VALIDATED);
        assertThat(page.textContent("main")).contains("No issues.", "No dry run has completed for this run.");

        page.locator("#main").focus();
        tabTo("All import runs", 1);
        page.keyboard().press("Enter");
        page.waitForURL(url -> url.endsWith("/admin/import-runs"));
        assertClean("Import runs");
        assertThat(page.locator("tbody th[scope=row]").count()).isEqualTo(2);

        page.locator("#main").focus();
        tabTo(DRY_RUN + " (import)", 4);
        page.keyboard().press("Enter");
        page.waitForURL(url -> url.endsWith("/admin/import-runs/" + DRY_RUN));
        assertClean("Import run " + DRY_RUN);
        assertThat(page.locator("table caption").allTextContents())
                .containsExactly("Issues per class, fatal first", "Figures of the last completed dry run, by figure");
        assertThat(page.textContent("main")).contains("NOT_SCANNED", "External: attachments are stored as not scanned");
    }

    /** From a page, Tab to the link with this name and follow it with Enter. */
    private void follow(String from, String link, String to) {
        page.navigate(KEYCLOAK.app(from));
        page.waitForLoadState();
        page.locator("#main").focus();
        tabTo(link, 40);
        page.keyboard().press("Enter");
        page.waitForURL(url -> url.endsWith(to));
    }

    private void seed() {
        Instant at = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        run(DRY_RUN, "DRY_RUN", at);
        run(VALIDATED, "VALIDATED", at.plusSeconds(60));
        jdbc.update("UPDATE IMPORT_RUN SET SCAN_MODE = 'EXTERNAL', ROWS_STAGED = 3 WHERE ID = ?", DRY_RUN);
        jdbc.update(
                "INSERT INTO IMPORT_ISSUE (ID, RUN_ID, STAGE, ENTITY, SEVERITY, CODE, DETAIL, CREATED_AT)"
                        + " VALUES (?, ?, 'DRY_RUN', 'ATTACHMENT', 'WARNING', 'NOT_SCANNED', 2, ?)",
                DRY_RUN,
                DRY_RUN,
                Timestamp.from(at));
        jdbc.update(
                "INSERT INTO IMPORT_RUN_SUMMARY (ID, RUN_ID, METRIC, DIMENSION, METRIC_VALUE)"
                        + " VALUES (?, ?, 'CASES', 'OPEN', 3)",
                DRY_RUN,
                DRY_RUN);
    }

    private void run(long id, String status, Instant at) {
        jdbc.update(
                "INSERT INTO IMPORT_RUN (ID, KIND, SOURCE_ID, STATUS, REQUESTED_BY, REQUESTED_AT, UPDATED_AT)"
                        + " VALUES (?, 'IMPORT', 'synthetic-a11y', ?, ?, ?, ?)",
                id,
                status,
                requester,
                Timestamp.from(at),
                Timestamp.from(at));
    }

    private void assertClean(String heading) {
        page.waitForLoadState();
        ShellPage shell = new ShellPage(page);
        assertThat(shell.heading()).isEqualTo(heading);
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
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
