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
 * Request Update (WP-18 part b) in a real browser after a real OIDC login as the dev realm's {@code alpha.analyst},
 * driven from the keyboard: Enter on "Request update" opens the dialog with focus in the recipient combobox; typing
 * narrows its listbox, Down highlights the one match (aria-activedescendant) and Enter chooses it; Tab reaches the
 * message, then Send, and Enter posts. The case page comes back with the toast and the open request, whose "Mark
 * answered" Enter answers. axe finds 0 violations with the dialog closed, open with the list expanded, after the
 * request and after the answer, with no CSP problem in the console.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class UpdateRequestAccessibilityIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String ALPHA_ANALYST = "00000000-0000-4000-8000-000000000002";
    private static final String TITLE = "Synthetic update request case";

    private static Playwright playwright;
    private static Browser browser;

    private final List<String> consoleProblems = new CopyOnWriteArrayList<>();
    private final List<Long> people = new ArrayList<>();
    private Page page;
    private long team;
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
        if (team == 0) {
            return;
        }
        String ofTeam = " WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = ?)";
        for (String table : new String[] {
            "UPDATE_REQUEST", "NOTIFICATION_OUTBOX", "USER_ALERT", "AUDIT_EVENT", "CASE_SEARCH", "CASE_SLA"
        }) {
            jdbc.update("DELETE FROM " + table + ofTeam, team);
        }
        jdbc.update("DELETE FROM CASE_RECORD WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", team);
        people.forEach(id -> jdbc.update("DELETE FROM APP_USER WHERE ID = ?", id));
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    @Test
    void updateIsRequestedThroughTheComboboxAndMarkedAnsweredByKeyboard() {
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
        long colleague = colleague(seed, TeamRole.ANALYST);
        colleague(seed, TeamRole.READER);
        CaseNumber number = cases.create(actor(analyst), fields(analyst));
        String name = "Seed User " + colleague;

        page.navigate(KEYCLOAK.app("/cases/" + number));
        assertClean();
        page.focus("a[data-tk-dialog-open='tk-request-dialog']");
        page.keyboard().press("Enter");
        assertThat(page.evaluate("() => document.activeElement.id")).isEqualTo("tk-recipient-input");
        assertThat(page.evaluate("() => document.activeElement.getAttribute('role')"))
                .isEqualTo("combobox");
        page.keyboard().type(String.valueOf(colleague));
        page.keyboard().press("ArrowDown");
        assertThat(page.getAttribute("#tk-recipient-input", "aria-expanded")).isEqualTo("true");
        assertThat(page.locator("#tk-recipient-listbox [role=option]:not([hidden])")
                        .count())
                .isEqualTo(1);
        assertThat(page.textContent("#" + page.getAttribute("#tk-recipient-input", "aria-activedescendant")))
                .isEqualTo(name);
        assertClean();
        page.keyboard().press("Enter");
        assertThat(page.inputValue("#tk-recipient-input")).isEqualTo(name);
        assertThat(page.getAttribute("#tk-recipient-input", "aria-expanded")).isEqualTo("false");
        assertThat(page.evaluate("() => document.getElementById('tk-recipient').value"))
                .isEqualTo(String.valueOf(colleague));
        page.keyboard().press("Tab");
        assertThat(page.evaluate("() => document.activeElement.id")).isEqualTo("tk-request-message");
        page.keyboard().type("A synthetic question for the colleague");
        page.keyboard().press("Tab");
        page.keyboard().press("Enter");
        page.waitForSelector("text=The update was requested.");
        assertClean();
        assertThat(page.textContent("#update-requests + ul")).contains("Update requested from " + name);

        page.focus("form[action$='/answered'] button");
        page.keyboard().press("Enter");
        page.waitForSelector("text=The update request was marked answered.");
        assertClean();
        assertThat(page.querySelector("#update-requests")).isNull();
    }

    private long colleague(CaseSeed seed, TeamRole role) {
        long id = seed.user();
        people.add(id);
        seed.member(id, team, role);
        return id;
    }

    private void assertClean() {
        page.waitForLoadState();
        ShellPage shell = new ShellPage(page);
        assertThat(shell.heading()).contains(TITLE);
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
