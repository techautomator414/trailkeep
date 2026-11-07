// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
 * A case turning at risk, seen in a real browser after a real OIDC login as its owner, {@code alpha.analyst} (WP-21
 * part c). The case's SLA row is set at risk since yesterday and due in three days, and the evaluator's chunk writer
 * moves that one case (not the whole table, which other tests share). The case page then reads "At risk, 3 days
 * left", the cases list filtered to At risk lists it, and the Alerts page carries its alert; axe finds 0 violations on
 * all three.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class SlaAccessibilityIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String ALPHA_ANALYST = "00000000-0000-4000-8000-000000000002";
    private static final String TITLE = "Synthetic SLA golden path";

    private static Playwright playwright;
    private static Browser browser;

    private final List<String> consoleProblems = new CopyOnWriteArrayList<>();
    private Page page;
    private long team;
    private long caseType;
    private long analyst;

    @Autowired
    private CaseService cases;

    @Autowired
    private SlaChunkWriter writer;

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
        // Test-only cleanup as the schema owner; the application user has no DELETE grant on AUDIT_EVENT.
        String ofTeam = " WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = ?)";
        for (String table : new String[] {
            "ALERT_LEDGER", "NOTIFICATION_OUTBOX", "USER_ALERT", "AUDIT_EVENT", "CASE_SEARCH", "CASE_SLA"
        }) {
            jdbc.update("DELETE FROM " + table + ofTeam, team);
        }
        jdbc.update("DELETE FROM CASE_RECORD WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    @Test
    void anAtRiskCaseShowsOnItsPageInTheFilteredListAndInTheAlerts() {
        page.navigate(KEYCLOAK.app("/"));
        new SignInPage(page).signIn("alpha.analyst", KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        page.waitForLoadState();
        consoleProblems.clear();
        CaseNumber number = atRiskCase();

        page.navigate(KEYCLOAK.app("/cases/" + number));
        assertClean(TITLE);
        assertThat(page.textContent(".tk-sla-AT_RISK")).contains("At risk, 3 days left");

        page.navigate(KEYCLOAK.app("/cases?sla=AT_RISK"));
        assertClean("Cases");
        assertThat(page.textContent("main")).contains(number.toString(), "At risk, 3 days left");

        page.navigate(KEYCLOAK.app("/alerts"));
        assertClean("Alerts");
        assertThat(page.textContent("main")).contains(number + ", \"" + TITLE + "\", is at risk: it is due on");
    }

    /** A case the analyst owns, at risk since yesterday and due in three days, moved on by the chunk writer. */
    private CaseNumber atRiskCase() {
        analyst = Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, ALPHA_ANALYST));
        CaseSeed seed = new CaseSeed(jdbc);
        team = seed.team();
        caseType = seed.caseType();
        seed.member(analyst, team, TeamRole.ANALYST);
        CaseNumber number = cases.create(
                new Actor(
                        new UserPermissions(analyst, false, Set.of(), Map.of(team, TeamRole.ANALYST)),
                        "Synthetic Analyst"),
                new CaseFields(
                        caseType,
                        TITLE,
                        CasePriority.NORMAL,
                        LocalDate.now(ZoneOffset.UTC).minusDays(10),
                        team,
                        analyst,
                        "Synthetic subject",
                        "Synthetic description",
                        Sensitivity.STANDARD));
        Instant now = Instant.now();
        long caseId = Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM CASE_RECORD WHERE CASE_NUMBER = ?", Long.class, number.toString()));
        jdbc.update(
                "UPDATE CASE_SLA SET AT_RISK_AT = ?, SLA_DUE_AT = ? WHERE CASE_ID = ?",
                Timestamp.from(now.minus(Duration.ofDays(1))),
                Timestamp.from(now.plus(Duration.ofDays(3)).plus(Duration.ofHours(1))),
                caseId);
        assertThat(writer.write(List.of(caseId), now).atRisk()).isOne();
        return number;
    }

    private void assertClean(String heading) {
        page.waitForLoadState();
        ShellPage shell = new ShellPage(page);
        assertThat(shell.heading()).contains(heading);
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }
}
