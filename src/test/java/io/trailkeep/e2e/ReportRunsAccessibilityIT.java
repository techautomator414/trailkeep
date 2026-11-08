// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.Media;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.e2e.pages.ShellPage;
import io.trailkeep.e2e.pages.SignInPage;
import io.trailkeep.report.ReportService;
import io.trailkeep.testsupport.KeycloakDevRealm;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.ReportFixture;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
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
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/**
 * The scheduled report runs (WP-23 part b1) in a real browser after a real OIDC login as the dev realm's {@code
 * alpha.manager1}, made a Manager of {@link ReportFixture}'s first team, with one stored run of that team holding the
 * aging report's own fragment, driven from the keyboard: from the aging page, Tab and Enter on "Scheduled reports"
 * open the list; Tab and Enter on the run open it, with the report's tables; Tab and Enter on its "Export CSV"
 * download the stored file; back on the list, Tab and Enter on the row's "Download CSV" (named for the run) download
 * it too. axe finds 0 violations on the list and the run, and the run prints without the chrome and the links.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class ReportRunsAccessibilityIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String ALPHA_MANAGER = "00000000-0000-4000-8000-000000000003";
    private static final long RUN = 984_801L;
    private static final String CSV = "﻿\"Team\",\"Owner\"\r\n\"Alpha Reporting Team\",\"Synthetic\"\r\n";

    private static Playwright playwright;
    private static Browser browser;

    private final List<String> consoleProblems = new CopyOnWriteArrayList<>();
    private Page page;
    private LocalDate today;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReportService reports;

    @Autowired
    private ITemplateEngine templates;

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
    void signInAsAManagerWithOneStoredRun() {
        page = browser.newContext(new Browser.NewContextOptions().setAcceptDownloads(true))
                .newPage();
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
        long manager = Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, ALPHA_MANAGER));
        today = LocalDate.now(ZoneOffset.UTC);
        new ReportFixture(jdbc, today, ZoneOffset.UTC).seed();
        jdbc.update(
                "INSERT INTO APP_USER_TEAM (USER_ID, TEAM_ID, ROLE_IN_TEAM) VALUES (?, ?, 'MANAGER')",
                manager,
                ReportFixture.ALPHA);
        Instant now = Instant.now();
        jdbc.update(
                "INSERT INTO REPORT_SCHEDULE (ID, TEAM_ID, RUN_WEEKDAY, RUN_HOUR, NEXT_RUN_AT) VALUES (?, ?, 1, 6, ?)",
                ReportFixture.ALPHA,
                ReportFixture.ALPHA,
                Timestamp.from(now.plus(Duration.ofDays(3))));
        jdbc.update(
                "INSERT INTO REPORT_RUN (ID, SCHEDULE_ID, TEAM_ID, REPORT, RAN_AT, AS_OF, LINE_COUNT, HTML, CSV)"
                        + " VALUES (?, ?, ?, 'AGING', ?, ?, 3, ?, ?)",
                RUN,
                ReportFixture.ALPHA,
                ReportFixture.ALPHA,
                Timestamp.from(now),
                Date.valueOf(today),
                agingFragment(),
                CSV);
        consoleProblems.clear();
    }

    @AfterEach
    void removeTheFixture() {
        page.context().close();
        // Test-only cleanup of the committed fixture, as the schema owner.
        String teams = " IN (" + ReportFixture.ALPHA + ", " + ReportFixture.BETA + ")";
        jdbc.update("DELETE FROM REPORT_RUN WHERE TEAM_ID" + teams);
        jdbc.update("DELETE FROM REPORT_SCHEDULE WHERE TEAM_ID" + teams);
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
    void theRunsAreReachedOpenedAndDownloadedFromTheKeyboardAndPrintWithoutTheChrome() throws IOException {
        page.navigate(KEYCLOAK.app("/reports/aging"));
        page.waitForLoadState();
        page.locator("#main").focus();
        tabTo("Scheduled reports", 4);
        page.keyboard().press("Enter");
        page.waitForURL(url -> url.endsWith("/reports/runs"));
        assertClean("Scheduled reports");
        assertThat(page.locator("table caption").textContent()).startsWith("Scheduled aging reports");
        assertThat(page.locator("tbody th[scope='row']").allTextContents()).containsExactly("Aging as of " + today);

        page.locator("#main").focus();
        tabTo("Aging as of " + today, 3);
        page.keyboard().press("Enter");
        page.waitForURL(url -> url.endsWith("/reports/runs/" + RUN));
        assertClean("Aging for " + ReportFixture.ALPHA_NAME + " as of " + today);
        assertThat(page.locator("table.tk-report caption").allTextContents())
                .hasSize(3)
                .contains("Overdue cases, oldest due first", "Unanswered update requests, oldest first");
        assertThat(page.locator("table.tk-report-list")
                        .first()
                        .locator("tbody th a")
                        .allTextContents())
                .containsExactly("RPT-2026-984105", "RPT-2026-984106", "RPT-2026-984102");
        assertThat(page.textContent("main")).doesNotContain(ReportFixture.MESSAGE);

        page.emulateMedia(new Page.EmulateMediaOptions().setMedia(Media.PRINT));
        assertThat(page.locator("table.tk-report").first().isVisible()).isTrue();
        assertThat(page.isVisible("header.tk-header")).isFalse();
        assertThat(page.isVisible(".tk-report-links")).isFalse();
        page.emulateMedia(new Page.EmulateMediaOptions().setMedia(Media.SCREEN));

        page.locator("#main").focus();
        tabTo("All scheduled reports", 1);
        tabTo("Export CSV", 1);
        assertDownloaded(page.waitForDownload(() -> page.keyboard().press("Enter")));

        page.locator("#main").focus();
        tabTo("All scheduled reports", 1);
        page.keyboard().press("Enter");
        page.waitForURL(url -> url.endsWith("/reports/runs"));
        page.locator("#main").focus();
        tabTo("Download CSV: " + ReportFixture.ALPHA_NAME + ", aging as of " + today, 4);
        assertDownloaded(page.waitForDownload(() -> page.keyboard().press("Enter")));
    }

    private void assertDownloaded(Download download) throws IOException {
        assertThat(download.suggestedFilename()).isEqualTo("aging-run-" + RUN + "-" + today + ".csv");
        assertThat(Files.readString(download.path(), StandardCharsets.UTF_8)).isEqualTo(CSV);
    }

    /** The aging report's own fragment for a Manager of the team, as the delivery job stores it. */
    private String agingFragment() {
        Actor reader = new Actor(
                new UserPermissions(0, false, Set.of(), Map.of(ReportFixture.ALPHA, TeamRole.MANAGER)), "Run");
        Context context = new Context(Locale.ROOT, Map.of("r", reports.aging(reader), "listed", ReportService.LISTED));
        return templates.process("report/aging", Set.of("report"), context);
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
