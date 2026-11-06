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
 * Comments in a real browser (WP-13, BUILD_PLAN §4.3, §10.1): the dev realm's {@code alpha.analyst} posts a blank
 * comment by keyboard and gets the 422 page with the focused error summary, then posts one with a link, code, a list
 * and a quote, which the case page shows rendered after the one-shot notice. Every state (empty, error, with
 * comments) has 0 axe violations and no CSP violation in the console. The analyst also pins a comment by keyboard,
 * which then shows in the pinned section and marked in the list with focus continuing at that comment, and unpins it;
 * both states are equally clean. The rows the test commits are removed
 * afterwards. Browser handling follows {@link ShellAccessibilityIT}.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class CaseCommentsIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String ALPHA_ANALYST = "00000000-0000-4000-8000-000000000002";
    private static final String TITLE = "Synthetic comment case";
    private static final String MARKDOWN = """
            See [the synthetic guide](https://example.test/guide) and run `tk --check`:

            - first *point*
            - second **point**

            > a quoted synthetic line

            ```
            code block line
            ```""";

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
            jdbc.update("DELETE FROM CASE_COMMENT_PIN" + ofTeam, team);
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
    void anAnalystMeetsTheErrorSummaryThenPostsAFormattedComment() {
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

        Response casePage = Objects.requireNonNull(page.navigate(KEYCLOAK.app("/cases/" + number)));
        assertThat(casePage.status()).isEqualTo(200);
        assertClean();
        assertThat(page.textContent("main")).contains("No comments yet.");

        page.focus("#body");
        page.keyboard().type("   ");
        page.keyboard().press("Tab");
        assertThat(page.evaluate("() => document.activeElement.type")).isEqualTo("submit");
        assertThat(page.waitForResponse(
                                response -> response.request().method().equals("POST"),
                                () -> page.keyboard().press("Enter"))
                        .status())
                .isEqualTo(422);
        page.waitForSelector("[data-tk-error-summary]");
        new ShellPage(page).waitForErrorSummaryFocus();
        assertThat(page.evaluate("() => document.activeElement.hasAttribute('data-tk-error-summary')"))
                .isEqualTo(true);
        assertThat(page.getAttribute("#body", "aria-invalid")).isEqualTo("true");
        assertClean();

        page.click("[data-tk-error-summary] a[href='#body']");
        new ShellPage(page).waitForFocusOn("body");
        assertThat(page.evaluate("() => document.activeElement.id")).isEqualTo("body");
        page.fill("#body", MARKDOWN);
        page.click("main form[action$='/comments'] button[type=submit]");
        page.waitForURL(url -> url.endsWith("/cases/" + number + "#comments"));
        assertClean();
        assertThat(page.textContent("[data-tk-toast]")).contains("Your comment was posted.");
        assertThat(page.getAttribute(".tk-comment-body a", "href")).isEqualTo("https://example.test/guide");
        assertThat(page.textContent(".tk-comment-body p code")).isEqualTo("tk --check");
        assertThat(page.locator(".tk-comment-body ul li").count()).isEqualTo(2);
        assertThat(page.textContent(".tk-comment-body blockquote")).contains("a quoted synthetic line");
        assertThat(page.textContent(".tk-comment-body pre code")).contains("code block line");
        assertThat(page.textContent(".tk-comments")).contains(" wrote on ");

        page.reload();
        assertClean();
        assertThat(page.querySelector("[data-tk-toast]")).isNull();
    }

    @Test
    void anAnalystPinsAndUnpinsACommentByKeyboard() {
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
        comments.post(actor(analyst), number.toString(), "A synthetic comment worth *pinning*");
        final long comment = Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM CASE_COMMENT WHERE CASE_ID = ?", Long.class, seed.caseId(number)));

        page.navigate(KEYCLOAK.app("/cases/" + number));
        assertClean();
        assertThat(page.querySelector("#pinned-comments")).isNull();
        page.focus("#comment-" + comment + " button[type=submit]");
        assertThat(page.evaluate("() => document.activeElement.textContent.trim()"))
                .isEqualTo("Pin comment");
        page.keyboard().press("Enter");
        page.waitForURL(url -> url.endsWith("/cases/" + number + "#comment-" + comment));
        assertClean();
        assertThat(page.textContent("[data-tk-toast]")).contains("The comment was pinned.");
        assertThat(page.textContent("#pinned-comments")).isEqualTo("Pinned comments");
        assertThat(page.textContent(".tk-pinned .tk-comment-body")).contains("A synthetic comment worth pinning");
        assertThat(page.textContent("#comment-" + comment)).contains("Pinned");
        page.keyboard().press("Tab");
        assertThat(page.evaluate("() => document.activeElement.textContent.trim()"))
                .as("sequential focus starts at the comment that was pinned")
                .isEqualTo("Unpin comment");
        assertThat(page.evaluate("() => document.activeElement.closest('li').id"))
                .isEqualTo("comment-" + comment);

        page.keyboard().press("Enter");
        page.waitForSelector("text=The comment was unpinned.");
        assertClean();
        assertThat(page.url()).endsWith("/cases/" + number + "#comment-" + comment);
        assertThat(page.textContent("[data-tk-toast]")).contains("The comment was unpinned.");
        assertThat(page.querySelector("#pinned-comments")).isNull();
        assertThat(page.textContent("#comment-" + comment)).doesNotContain("Pinned");
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
