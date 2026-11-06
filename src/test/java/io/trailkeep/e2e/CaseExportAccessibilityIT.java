// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.ReducedMotion;
import io.trailkeep.casefile.CaseFields;
import io.trailkeep.casefile.CasePriority;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.e2e.pages.CasesListPage;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
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
 * The CSV export control (WP-15 part 4a) in a real browser after a real OIDC login as the dev realm's {@code
 * compliance.admin}, driven from the keyboard: on a searched list, Tab to "Include subject and description", Space
 * ticks it, Tab to "Export CSV", Enter downloads {@code cases-<yyyyMMdd-HHmm>.csv} with a byte order mark, the
 * searched case and its details. axe finds 0 violations on the page with the control, and the console reports no CSP
 * problem.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class CaseExportAccessibilityIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();

    private static Playwright playwright;
    private static Browser browser;

    private final List<String> consoleProblems = new CopyOnWriteArrayList<>();
    private Page page;

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
        // Reduced motion makes app.css end transitions at once, so axe never samples a colour mid-transition.
        page = browser.newContext(
                        new Browser.NewContextOptions().setAcceptDownloads(true).setReducedMotion(ReducedMotion.REDUCE))
                .newPage();
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

    @Test
    void theExportIsDownloadedFromTheKeyboardWithDetailsAndNoViolations() throws IOException {
        String word = "Godwit" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        CaseSeed seed = new CaseSeed(jdbc);
        long team = seed.team();
        long analyst = seed.user();
        seed.member(analyst, team, TeamRole.ANALYST);
        CaseService.Actor actor = new CaseService.Actor(
                new UserPermissions(analyst, false, Set.of(), Map.of(team, TeamRole.ANALYST)), "Synthetic Analyst");
        final CaseNumber number = cases.create(
                actor,
                new CaseFields(
                        seed.caseType(),
                        "Exported " + word,
                        CasePriority.NORMAL,
                        LocalDate.now(ZoneOffset.UTC).minusDays(1),
                        team,
                        analyst,
                        "Synthetic exported subject",
                        "Synthetic exported description",
                        Sensitivity.STANDARD));
        page.navigate(KEYCLOAK.app("/cases?q=" + word));
        page.waitForLoadState();
        ShellPage shell = new ShellPage(page);
        CasesListPage list = new CasesListPage(page);
        assertThat(list.status()).isEqualTo("1 case found.");
        assertThat(shell.axeViolations()).isEmpty();

        page.locator("#tk-q").focus();
        list.tabTo("tk-export-details", 20);
        page.keyboard().press("Space");
        assertThat(page.isChecked("#tk-export-details")).isTrue();
        list.tabTo("tk-export-csv", 1);
        Download download = page.waitForDownload(() -> page.keyboard().press("Enter"));

        assertThat(download.suggestedFilename()).matches("cases-\\d{8}-\\d{4}\\.csv");
        String csv = Files.readString(Objects.requireNonNull(download.path()), StandardCharsets.UTF_8);
        assertThat(csv)
                .startsWith("\uFEFF\"Case number\"")
                .contains("\"" + number + "\",\"Exported " + word + "\"")
                .contains("\"Synthetic exported subject\",\"Synthetic exported description\"\r\n");
        assertThat(page.url()).contains("q=" + word);
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }
}
