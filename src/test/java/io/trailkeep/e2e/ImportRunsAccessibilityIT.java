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
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
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
 * empty list, the list, and both run pages, with no CSP problem. The dry-run run's page has its closure worklist (part
 * y3), whose case number link Tab reaches.
 *
 * <p>The run request and the sign-off (part s2): Tab and Enter on "Request an import run" open the native dialog with
 * focus in the source id; a malformed id comes back as the request page with the error summarized, and a corrected one
 * opens the run. On a completed dry run, Space ticks one warning class and Enter on "Sign off" comes back with the
 * missing acknowledgement summarized; a dry run that ran again meanwhile makes the form stale, which comes back as a
 * conflict with Reload; after Reload, ticking every class and Enter signs the run off. axe finds 0 violations in the
 * open dialog, the refused request, the new run, the sign-off form, the refused sign-off, the conflict and the
 * signed-off run.
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
    private long team;

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
        jdbc.update("DELETE FROM IMPORT_RUN WHERE ID IN (?, ?) OR SOURCE_ID = 'legacy-k1'", DRY_RUN, VALIDATED);
        if (team != 0) {
            List<Long> types = jdbc.queryForList(
                    "SELECT DISTINCT CASE_TYPE_ID FROM CASE_RECORD WHERE TEAM_ID = ?", Long.class, team);
            jdbc.update("DELETE FROM CASE_RECORD WHERE TEAM_ID = ?", team);
            for (long type : types) {
                Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, type);
                jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", type);
                jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
            }
            jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        }
        jdbc.update("DELETE FROM APP_USER WHERE ID = ?", requester);
    }

    @Test
    void importRunsAreReachedAndReadFromTheKeyboard() {
        // The empty state needs no runs at all: remove anything another test left in the shared database, children
        // first (test-only cleanup over the fixed table names of this list).
        for (String table : List.of(
                "IMPORT_LEDGER",
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
        final CaseNumber closed = closedInSource();
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
                .containsExactly(
                        "Issues per class, fatal first",
                        "Cases closed in the source, by case number",
                        "Figures of the last completed dry run, by figure");
        assertThat(page.textContent("main")).contains("NOT_SCANNED", "External: attachments are stored as not scanned");
        assertThat(page.textContent("#tk-worklist-title")).isEqualTo("Closure worklist");
        page.locator("#main").focus();
        tabTo(closed.toString(), 2);
        assertThat(page.evaluate("() => document.activeElement.getAttribute('href')"))
                .isEqualTo("/cases/" + closed);
    }

    @Test
    void runsAreRequestedAndSignedOffFromTheKeyboard() {
        page.navigate(KEYCLOAK.app("/admin/import-runs"));
        page.locator("#main").focus();
        tabTo("Request an import run", 3);
        page.keyboard().press("Enter");
        assertThat(page.evaluate("() => document.getElementById('tk-request-dialog').open"))
                .isEqualTo(true);
        assertThat(page.evaluate("() => document.activeElement.id")).isEqualTo("tk-source-id");
        assertClean("Import runs");

        page.keyboard().type("Legacy K1");
        page.keyboard().press("Enter");
        page.locator("[data-tk-error-summary]").waitFor();
        assertClean("Request an import run");
        tabToId("tk-source-id", 5);
        page.keyboard().press("Control+A");
        page.keyboard().type("legacy-k1");
        page.keyboard().press("Enter");
        page.waitForURL(url -> url.matches(".*/admin/import-runs/\\d+$"));
        assertThat(page.textContent("main")).contains("Requested", "No completed dry run");
        assertClean(String.valueOf(page.textContent("h1")).strip());

        seed();
        page.navigate(KEYCLOAK.app("/admin/import-runs/" + DRY_RUN));
        assertClean("Import run " + DRY_RUN);
        page.locator("#main").focus();
        tabToId("tk-ack-0", 40);
        page.keyboard().press("Space");
        tabTo("Sign off import run " + DRY_RUN, 2);
        page.keyboard().press("Enter");
        page.waitForURL(url -> url.endsWith("/sign-off"));
        assertClean("Import run " + DRY_RUN);
        assertThat(page.textContent("main")).contains("Not signed off: acknowledge every warning class.");

        // Someone runs the dry run again meanwhile: the form is stale, so it comes back as a conflict with Reload.
        jdbc.update("UPDATE IMPORT_RUN SET VERSION = VERSION + 1 WHERE ID = ?", DRY_RUN);
        page.locator("#main").focus();
        tabToId("tk-ack-1", 40);
        page.keyboard().press("Space");
        tabTo("Sign off import run " + DRY_RUN, 2);
        page.keyboard().press("Enter");
        page.locator("#tk-conflict-title").waitFor();
        assertClean("Import run " + DRY_RUN);
        assertThat(page.locator("form[action$='/sign-off']").count()).isZero();
        tabTo("Reload", 40);
        page.keyboard().press("Enter");
        page.waitForURL(url -> url.endsWith("/admin/import-runs/" + DRY_RUN));

        page.locator("#main").focus();
        tabToId("tk-ack-0", 40);
        page.keyboard().press("Space");
        tabToId("tk-ack-1", 1);
        page.keyboard().press("Space");
        tabTo("Sign off import run " + DRY_RUN, 1);
        page.keyboard().press("Enter");
        page.locator("text=This run is signed off.").waitFor();
        assertClean("Import run " + DRY_RUN);
        assertThat(page.textContent("main")).contains("Signed off, waiting to load", "This run is signed off.");
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
                "INSERT INTO IMPORT_ISSUE (ID, RUN_ID, STAGE, ENTITY, SEVERITY, CODE, CREATED_AT)"
                        + " VALUES (?, ?, 'DRY_RUN', 'PERSON', 'WARNING', 'AMBIGUOUS_PERSON', ?)",
                DRY_RUN + 1,
                DRY_RUN,
                Timestamp.from(at));
        jdbc.update(
                "INSERT INTO IMPORT_RUN_SUMMARY (ID, RUN_ID, METRIC, DIMENSION, METRIC_VALUE)"
                        + " VALUES (?, ?, 'CASES', 'OPEN', 3)",
                DRY_RUN,
                DRY_RUN);
    }

    /** A committed open case of a new team, a {@code CLOSED_IN_SOURCE} issue of the dry-run run (WP-26 part y3). */
    private CaseNumber closedInSource() {
        CaseSeed seed = new CaseSeed(jdbc);
        team = seed.team();
        CaseNumber number = seed.caseRow(team, requester, Sensitivity.STANDARD, CaseStatus.OPEN);
        jdbc.update(
                "INSERT INTO IMPORT_ISSUE (ID, RUN_ID, STAGE, ENTITY, SEVERITY, CODE, DETAIL, CREATED_AT)"
                        + " VALUES (?, ?, 'DRY_RUN', 'CASE', 'WARNING', 'CLOSED_IN_SOURCE', ?, CURRENT_TIMESTAMP)",
                DRY_RUN + 2,
                DRY_RUN,
                seed.caseId(number));
        return number;
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
