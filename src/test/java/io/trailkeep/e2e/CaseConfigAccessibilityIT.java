// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import io.trailkeep.e2e.pages.ShellPage;
import io.trailkeep.e2e.pages.SignInPage;
import io.trailkeep.testsupport.KeycloakDevRealm;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
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
 * The SLA policy and case type screens (WP-10) in a real browser after a real OIDC login as the dev realm's {@code
 * compliance.admin}: each list, each edit page, and each in its 422 invalid-input state have 0 axe violations and no
 * CSP violation in the console. Browser handling follows {@link ShellAccessibilityIT}.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class CaseConfigAccessibilityIT {

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
    void signInAsComplianceAdmin() {
        page = browser.newContext().newPage();
        page.onConsoleMessage(message -> {
            if (message.text().contains("Content Security Policy")
                    || message.text().contains("Refused to")) {
                consoleProblems.add(message.text());
            }
        });
        page.onPageError(error -> consoleProblems.add(error));
        page.navigate(KEYCLOAK.app("/admin/sla-policies"));
        new SignInPage(page).signIn("compliance.admin", KeycloakDevRealm.PASSWORD, KEYCLOAK.app(""));
        page.waitForLoadState();
        consoleProblems.clear();
    }

    @Test
    void slaPolicyScreensAndTheirInvalidStatesHaveNoViolations() {
        String policy = createPolicy();
        assertClean(policy);
        assertInvalid(policy);

        page.navigate(KEYCLOAK.app("/admin/sla-policies"));
        assertClean("SLA policies");
        assertInvalid("SLA policies");
    }

    @Test
    void caseTypeScreensAndTheirInvalidStatesHaveNoViolations() {
        createPolicy();
        page.navigate(KEYCLOAK.app("/admin/case-types"));
        assertClean("Case types");
        assertInvalid("Case types");

        String type = "Type " + UUID.randomUUID().toString().substring(0, 8);
        page.navigate(KEYCLOAK.app("/admin/case-types"));
        page.fill("#name", type);
        page.fill("#outcomes", "Resolved\nDuplicate");
        page.click("main form button[type=submit]");
        page.waitForURL(url -> url.matches(".*/admin/case-types/\\d+$"));
        assertClean(type);
        assertInvalid(type);
    }

    private String createPolicy() {
        String name = "Policy " + UUID.randomUUID().toString().substring(0, 8);
        page.navigate(KEYCLOAK.app("/admin/sla-policies"));
        page.fill("#name", name);
        page.click("main form button[type=submit]");
        page.waitForURL(url -> url.matches(".*/admin/sla-policies/\\d+$"));
        return name;
    }

    /** Submits the page's form with a blank (whitespace) name, which the browser accepts and the server refuses. */
    private void assertInvalid(String heading) {
        if (page.isVisible("#outcomes") && page.inputValue("#outcomes").isBlank()) {
            page.fill("#outcomes", "Resolved"); // valid, so only the name is refused
        }
        Response invalid = submit("#name", "   ", "main form button[type=submit]");
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(page.isVisible("[role=alert]")).isTrue();
        assertClean(heading);
    }

    private Response submit(String field, String value, String button) {
        page.fill(field, value);
        return page.waitForResponse(response -> response.request().method().equals("POST"), () -> page.click(button));
    }

    private void assertClean(String heading) {
        page.waitForLoadState();
        ShellPage shell = new ShellPage(page);
        assertThat(shell.heading()).isEqualTo(heading);
        assertThat(shell.axeViolations()).isEmpty();
        assertThat(consoleProblems).isEmpty();
    }
}
