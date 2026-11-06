// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import io.trailkeep.casefile.CaseCommentService;
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
 * The History page in a real browser (WP-16, BUILD_PLAN §4.3, §10.1): the dev realm's {@code alpha.analyst} reaches
 * it from the case page by keyboard (Enter on the History link), finds a captioned table with column and row headers
 * and a {@code time} element per event, narrows it to comments with the native select and the button by keyboard,
 * and every state has 0 axe violations and no CSP violation in the console. The rows the test commits are removed
 * afterwards. Browser handling follows {@link ShellAccessibilityIT}.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class CaseHistoryAccessibilityIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String ALPHA_ANALYST = "00000000-0000-4000-8000-000000000002";
    private static final String TITLE = "Synthetic history case";

    private static Playwright playwright;
    private static Browser browser;

    private final List<String> consoleProblems = new CopyOnWriteArrayList<>();
    private Page page;
    private long team;
    private long caseType;

    @Autowired
    private CaseService cases;

    @Autowired
    private CaseCommentService comments;

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
        // Test-only cleanup as the schema owner; the application user may not delete comments or audit events.
        String ofTeam = " WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = ?)";
        if (team != 0) {
            jdbc.update("DELETE FROM AUDIT_EVENT" + ofTeam, team);
            jdbc.update("DELETE FROM CASE_COMMENT" + ofTeam, team);
            jdbc.update("DELETE FROM CASE_SEARCH" + ofTeam, team);
            jdbc.update("DELETE FROM CASE_SLA" + ofTeam, team);
            jdbc.update("DELETE FROM CASE_RECORD WHERE TEAM_ID = ?", team);
            jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", team);
            jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
            Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
            jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
            jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
        }
    }

    @Test
    void analystReachesTheHistoryByKeyboardAndNarrowsItToComments() {
        page.navigate(KEYCLOAK.app("/"));
        new SignInPage(page).signIn("alpha.analyst", KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        page.waitForLoadState();
        consoleProblems.clear();
        long analyst = Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, ALPHA_ANALYST));
        CaseSeed seed = new CaseSeed(jdbc);
        team = seed.team();
        caseType = seed.caseType();
        seed.member(analyst, team, TeamRole.ANALYST);
        CaseNumber number = cases.create(actor(analyst), fields(analyst));
        comments.post(actor(analyst), number.toString(), "A synthetic comment for the history");

        Response casePage = Objects.requireNonNull(page.navigate(KEYCLOAK.app("/cases/" + number)));
        assertThat(casePage.status()).isEqualTo(200);
        assertThat(new ShellPage(page).axeViolations()).isEmpty();

        // From the case page: focus the History link and follow it with Enter.
        page.focus("#tk-history-link");
        page.keyboard().press("Enter");
        page.waitForURL(url -> url.endsWith("/cases/" + number + "/history"));
        assertHistoryClean(number);
        assertThat(page.textContent("#tk-history-status")).isEqualTo("2 events found.");
        assertThat(page.textContent("caption")).contains("newest first");
        assertThat(page.locator("tbody tr").count()).isEqualTo(2);
        assertThat(page.locator("tbody th[scope=row] time[datetime]").count()).isEqualTo(2);
        assertThat(page.textContent("tbody")).contains("Comment added", "Case created", "Synthetic Analyst");

        // Tab to the kind select, choose Comments by typing, Tab to the button and press Enter.
        tabTo("tk-history-kind");
        page.keyboard().type("Comm");
        assertThat(page.inputValue("#tk-history-kind")).isEqualTo("comments");
        page.keyboard().press("Tab");
        assertThat(page.evaluate("() => document.activeElement.textContent.trim()"))
                .isEqualTo("Show events");
        page.keyboard().press("Enter");
        page.waitForURL(url -> url.contains("kind=comments"));
        assertHistoryClean(number);
        assertThat(page.textContent("#tk-history-status")).isEqualTo("1 event found.");
        assertThat(page.locator("tbody tr").count()).isEqualTo(1);
        assertThat(page.textContent("tbody")).contains("Comment added").doesNotContain("Case created");
        assertThat(page.inputValue("#tk-history-kind")).isEqualTo("comments");
    }

    private void tabTo(String id) {
        for (int i = 0; i < 12; i++) {
            page.keyboard().press("Tab");
            if (id.equals(page.evaluate("() => document.activeElement.id"))) {
                return;
            }
        }
        throw new AssertionError("Tab never reached #" + id);
    }

    private void assertHistoryClean(CaseNumber number) {
        page.waitForLoadState();
        ShellPage shell = new ShellPage(page);
        assertThat(shell.heading()).isEqualTo("History of " + number);
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }

    private CaseService.Actor actor(long userId) {
        return new CaseService.Actor(
                new UserPermissions(userId, false, Set.of(), Map.of(team, TeamRole.ANALYST)), "Synthetic Analyst");
    }

    private CaseFields fields(long owner) {
        return new CaseFields(
                caseType,
                TITLE,
                CasePriority.NORMAL,
                LocalDate.now(ZoneOffset.UTC).minusDays(2),
                team,
                owner,
                "Synthetic subject",
                "Synthetic description",
                Sensitivity.STANDARD);
    }
}
