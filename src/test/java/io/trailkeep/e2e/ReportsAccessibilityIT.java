// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.Media;
import io.trailkeep.e2e.pages.ShellPage;
import io.trailkeep.e2e.pages.SignInPage;
import io.trailkeep.testsupport.KeycloakDevRealm;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.ReportFixture;
import io.trailkeep.testsupport.RequiresDocker;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
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
 * The report pages (WP-22 part a) in a real browser after a real OIDC login as the dev realm's {@code alpha.manager1},
 * made a Manager of {@link ReportFixture}'s first team, driven from the keyboard: Tab from the brand reaches the
 * header's "Reports", Enter opens Aging (with part c's tables of overdue cases and unanswered update requests, in
 * their order and without any request's message), Tab and Enter on "Export CSV" download its CSV (part b: the file's
 * name and cells checked), Tab and Enter follow its link to SLA compliance; the compliance form is reached by Tab
 * and a backwards period sent with Enter comes back marked. axe finds 0 violations on each, and the print view shows
 * the tables without the header, the footer, the links or the form.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class ReportsAccessibilityIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String ALPHA_MANAGER = "00000000-0000-4000-8000-000000000003";

    private static Playwright playwright;
    private static Browser browser;

    private final List<String> consoleProblems = new CopyOnWriteArrayList<>();
    private Page page;
    private long manager;

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
    void signInAsAManagerOfTheFixturesTeam() {
        page = browser.newContext().newPage();
        page.onConsoleMessage(message -> {
            if (message.text().contains("Content Security Policy")
                    || message.text().contains("Refused to")) {
                consoleProblems.add(message.text());
            }
        });
        page.onPageError(error -> consoleProblems.add(error));
        page.navigate(KEYCLOAK.app("/"));
        new SignInPage(page).signIn("alpha.manager1", KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        page.waitForLoadState();
        manager = Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, ALPHA_MANAGER));
        new ReportFixture(jdbc, LocalDate.now(ZoneOffset.UTC), ZoneOffset.UTC).seed();
        jdbc.update(
                "INSERT INTO APP_USER_TEAM (USER_ID, TEAM_ID, ROLE_IN_TEAM) VALUES (?, ?, 'MANAGER')",
                manager,
                ReportFixture.ALPHA);
        consoleProblems.clear();
    }

    @AfterEach
    void removeTheFixture() {
        page.context().close();
        // Test-only cleanup of the committed fixture, as the schema owner.
        String teams = " IN (" + ReportFixture.ALPHA + ", " + ReportFixture.BETA + ")";
        jdbc.update(
                "DELETE FROM UPDATE_REQUEST WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID" + teams + ")");
        jdbc.update("DELETE FROM CASE_SLA WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID" + teams + ")");
        jdbc.update("DELETE FROM CASE_RECORD WHERE TEAM_ID" + teams);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID" + teams);
        jdbc.update("DELETE FROM TEAM WHERE ID" + teams);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID IN (984021, 984022)");
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = 984031");
        jdbc.update("DELETE FROM APP_USER_GLOBAL_ROLE WHERE USER_ID BETWEEN 984001 AND 984009");
        jdbc.update("DELETE FROM APP_USER WHERE ID BETWEEN 984001 AND 984009");
    }

    @Test
    void theReportsAreReachedFromTheKeyboardDownloadAndPrintWithoutTheChrome() throws IOException {
        page.navigate(KEYCLOAK.app("/"));
        page.waitForLoadState();
        page.locator("a.tk-brand").focus();
        tabTo("Reports", 15);
        page.keyboard().press("Enter");
        page.waitForURL(url -> url.endsWith("/reports/aging"));
        assertClean("Aging");
        Locator counts = page.locator("table.tk-report").first();
        assertThat(counts.locator("caption").textContent()).startsWith("Open and pending-closure cases");
        assertThat(counts.locator("tbody th[scope='row']").allTextContents())
                .containsOnly(ReportFixture.ALPHA_NAME)
                .hasSize(3);
        assertThat(counts.locator("tbody td:first-of-type").allTextContents())
                .containsExactly("Synthetic Report Analyst", "Synthetic Report Manager", "Synthetic Restricted Owner");
        assertThat(page.locator("tfoot td").allTextContents()).containsExactly("2", "3", "2", "1", "2", "2", "3", "1");
        assertThat(page.locator("table.tk-report-list caption").allTextContents())
                .containsExactly("Overdue cases, oldest due first", "Unanswered update requests, oldest first");
        assertThat(page.locator("table.tk-report-list")
                        .first()
                        .locator("tbody th[scope='row']")
                        .allTextContents())
                .containsExactly("RPT-2026-984105", "RPT-2026-984106", "RPT-2026-984102");
        assertThat(page.locator("table.tk-report-list")
                        .last()
                        .locator("tbody th[scope='row']")
                        .allTextContents())
                .containsExactly("RPT-2026-984106", "RPT-2026-984100");
        assertThat(page.textContent("main")).doesNotContain(ReportFixture.MESSAGE);

        page.emulateMedia(new Page.EmulateMediaOptions().setMedia(Media.PRINT));
        assertThat(counts.isVisible()).isTrue();
        assertThat(page.locator("table.tk-report-list").last().isVisible()).isTrue();
        assertThat(page.isVisible("header.tk-header")).isFalse();
        assertThat(page.isVisible("footer.tk-footer")).isFalse();
        assertThat(page.isVisible(".tk-report-links")).isFalse();
        page.emulateMedia(new Page.EmulateMediaOptions().setMedia(Media.SCREEN));

        page.locator("#main").focus();
        tabTo("SLA compliance", 3);
        tabTo("Export CSV", 1);
        Download download = page.waitForDownload(() -> page.keyboard().press("Enter"));
        assertThat(download.suggestedFilename()).isEqualTo("aging-" + LocalDate.now(ZoneOffset.UTC) + ".csv");
        assertThat(Files.readString(download.path(), StandardCharsets.UTF_8))
                .startsWith("﻿\"Team\",\"Owner\",")
                .contains("\"Alpha Reporting Team\",\"Synthetic Restricted Owner\",\"0\",\"1\",")
                .contains("\"Total\",\"\",\"2\",\"3\",\"2\",\"1\",\"2\",\"2\",\"3\",\"1\"\r\n\r\n\"Overdue case\",")
                .endsWith("\"Synthetic report case 984100\",\"Synthetic Report Analyst\",\"Synthetic Report Manager\","
                        + "\"" + LocalDate.now(ZoneOffset.UTC).minusDays(3) + "\",\"3\"\r\n");

        page.locator("#main").focus();
        tabTo("SLA compliance", 3);
        page.keyboard().press("Enter");
        page.waitForURL(url -> url.endsWith("/reports/compliance"));
        assertClean("SLA compliance");
    }

    @Test
    void backwardsPeriodsSentFromTheKeyboardComeBackMarked() {
        page.navigate(KEYCLOAK.app("/reports/compliance"));
        assertClean("SLA compliance");
        assertThat(page.locator("tfoot td").allTextContents()).hasSize(5);

        page.emulateMedia(new Page.EmulateMediaOptions().setMedia(Media.PRINT));
        assertThat(page.isVisible("form.tk-report-form")).isFalse();
        assertThat(page.isVisible("table.tk-report")).isTrue();
        page.emulateMedia(new Page.EmulateMediaOptions().setMedia(Media.SCREEN));

        page.locator("#main").focus();
        tabTo("Aging", 1);
        tabTo("Export CSV", 1);
        tabToId("tk-from", 1);
        page.fill("#tk-from", "2026-02-01");
        // A date field's month, day and year are Tab stops of their own in Chromium.
        tabToId("tk-to", 4);
        page.fill("#tk-to", "2026-01-31");
        tabTo("Show", 4);
        page.keyboard().press("Enter");
        page.waitForURL(url -> url.contains("from=2026-02-01"));
        assertClean("SLA compliance");
        assertThat(page.isVisible("#tk-period-error")).isTrue();
        assertThat(page.getAttribute("#tk-from", "aria-invalid")).isEqualTo("true");
        assertThat(page.inputValue("#tk-to")).isEqualTo("2026-01-31");
        assertThat(page.querySelector("table.tk-report")).isNull();
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
