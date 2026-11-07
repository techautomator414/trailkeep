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
import io.trailkeep.user.TeamRole;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * The dashboard in a real browser after a real OIDC login as {@code alpha.analyst} (WP-20): the person owns an at-risk
 * case and has 21 open update requests addressed to them. From the keyboard: Tab moves through the five tiles in
 * order, each a count and a label; Enter on "At risk" opens the cases list filtered to at risk, which lists the case;
 * back on the dashboard, Enter on the update requests' "Next page" shows page 2 of 2. axe finds 0 violations on the
 * dashboard, its second page and the filtered list.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class DashboardAccessibilityIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String ALPHA_ANALYST = "00000000-0000-4000-8000-000000000002";

    private static Playwright playwright;
    private static Browser browser;

    private final List<String> consoleProblems = new CopyOnWriteArrayList<>();
    private Page page;
    private long team;
    private long caseType;
    private long colleague;

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
        page.context().close();
        if (team == 0) {
            return;
        }
        String ofTeam = " WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = ?)";
        for (String table : new String[] {"UPDATE_REQUEST", "CASE_SLA"}) {
            jdbc.update("DELETE FROM " + table + ofTeam, team);
        }
        jdbc.update("DELETE FROM CASE_RECORD WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM APP_USER WHERE ID = ?", colleague);
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    @Test
    void theTilesAndThePagingWorkFromTheKeyboard() {
        page.navigate(KEYCLOAK.app("/"));
        new SignInPage(page).signIn("alpha.analyst", KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        page.waitForLoadState();
        consoleProblems.clear();
        CaseNumber atRisk = seed();

        page.navigate(KEYCLOAK.app("/"));
        assertClean("Dashboard");
        page.focus(".tk-tile >> nth=0");
        List<String> tiles = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tiles.add(String.valueOf(page.evaluate("() => document.activeElement.textContent.trim()")));
            page.keyboard().press("Tab");
        }
        assertThat(tiles)
                .containsExactly(
                        "1 My open cases",
                        "1 At risk",
                        "0 Overdue",
                        "0 Pending my approval",
                        "21 Update requests for me");

        page.focus(".tk-tile >> nth=1");
        page.keyboard().press("Enter");
        page.waitForURL("**/cases?sla=AT_RISK**");
        assertClean("Cases");
        assertThat(page.textContent("main")).contains(atRisk.toString());

        page.navigate(KEYCLOAK.app("/"));
        page.focus("nav[aria-label='Pages of update requests'] a[rel='next']");
        page.keyboard().press("Enter");
        page.waitForSelector("text=Page 2 of 2.");
        assertClean("Dashboard");
    }

    /** The analyst's at-risk case, and 21 cases of a colleague's on which an update is asked of the analyst. */
    private CaseNumber seed() {
        long analyst = Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, ALPHA_ANALYST));
        CaseSeed seed = new CaseSeed(jdbc);
        team = seed.team();
        caseType = seed.caseType();
        colleague = seed.user();
        seed.member(analyst, team, TeamRole.ANALYST);
        seed.member(colleague, team, TeamRole.ANALYST);
        Instant now = Instant.now();
        CaseNumber atRisk = seed.caseRow(team, analyst, Sensitivity.STANDARD, CaseStatus.OPEN);
        sla(seed.caseId(atRisk), "AT_RISK", now.plus(Duration.ofDays(3)));
        for (int i = 0; i < 21; i++) {
            long caseId = seed.caseId(seed.caseRow(team, colleague, Sensitivity.STANDARD, CaseStatus.OPEN));
            sla(caseId, "ON_TRACK", now.plus(Duration.ofDays(30)));
            jdbc.update(
                    "INSERT INTO UPDATE_REQUEST (ID, CASE_ID, REQUESTED_BY, REQUESTED_FROM, MESSAGE, REQUESTED_AT,"
                            + " OPEN_KEY) VALUES (?, ?, ?, ?, 'Synthetic question', ?, ?)",
                    caseId * 10,
                    caseId,
                    colleague,
                    analyst,
                    Timestamp.from(now.minus(Duration.ofMinutes(i))),
                    caseId + ":" + analyst);
        }
        return atRisk;
    }

    private void sla(long caseId, String state, Instant due) {
        jdbc.update(
                "INSERT INTO CASE_SLA (CASE_ID, SLA_STATE, SLA_DUE_AT, AT_RISK_AT, SLA_CYCLE) VALUES (?, ?, ?, ?, 1)",
                caseId,
                state,
                Timestamp.from(due),
                Timestamp.from(due.minus(Duration.ofDays(5))));
    }

    private void assertClean(String heading) {
        page.waitForLoadState();
        ShellPage shell = new ShellPage(page);
        assertThat(shell.heading()).contains(heading);
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }
}
