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
import io.trailkeep.testsupport.KeycloakDevRealm;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The audit screen in a real browser after a real OIDC login as {@code compliance.admin} (#274): reached from the case
 * types page by keyboard, it has one h1 and a captioned table of events (the sign-in itself is one) with row headers;
 * the filters are labelled and submit from the keyboard; a range that is too long is announced next to the dates.
 * axe finds 0 violations and the Nu checker 0 errors in each state, with no CSP problem in the console.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class AuditAccessibilityIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();

    private static Playwright playwright;
    private static Browser browser;

    private final List<String> consoleProblems = new CopyOnWriteArrayList<>();
    private Page page;

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
    void signIn() {
        page = ShellPage.recorded(browser.newContext().newPage());
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

    @AfterEach
    void closeContext() {
        page.context().close();
    }

    @Test
    void theAuditScreenIsReachedAndFilteredByKeyboardAndHasNoViolations() {
        page.navigate(KEYCLOAK.app("/admin/case-types"));
        page.waitForLoadState();
        page.focus("a[href='/admin/audit']");
        page.keyboard().press("Enter");
        page.waitForURL("**/admin/audit");
        page.waitForLoadState();

        ShellPage shell = new ShellPage(page);
        assertThat(shell.heading()).isEqualTo("Audit trail");
        assertThat(page.locator("table caption").textContent()).isEqualTo("Audit events, newest first");
        assertThat(page.locator("tbody th[scope=row] time").count()).isGreaterThanOrEqualTo(1);
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();

        // Filter by action from the keyboard: type it, Enter submits the GET form.
        page.focus("#tk-audit-action");
        page.keyboard().type("login");
        page.keyboard().press("Enter");
        page.waitForURL("**/admin/audit?*action=login*");
        page.waitForLoadState();
        assertThat(page.locator("tbody td").allTextContents()).contains("LOGIN");
        assertThat(shell.axeViolations()).isEmpty();

        // A range of more than 31 days: the form again, the dates marked invalid and the reason tied to them.
        page.navigate(KEYCLOAK.app("/admin/audit?from=2026-01-01&to=2026-03-01"));
        page.waitForLoadState();
        assertThat(page.getAttribute("#tk-audit-from", "aria-invalid")).isEqualTo("true");
        assertThat(page.getAttribute("#tk-audit-from", "aria-describedby")).contains("tk-audit-range-error");
        assertThat(page.textContent("#tk-audit-range-error")).contains("at most 31 days");
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }
}
