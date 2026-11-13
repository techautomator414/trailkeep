// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import io.trailkeep.e2e.pages.ShellPage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code reconcile.html} (WP-26 part aa3, BUILD_PLAN §10.2) and {@code dry-run.html} (part ab2) in a real browser:
 * axe-core finds 0 WCAG 2.x A/AA violations, for reports with rows and for empty ones, and the pages run no script.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
class ReconcileReportAccessibilityIT {

    private static @Nullable Playwright playwright;
    private static @Nullable Browser browser;

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

    @Test
    void theReportPageHasNoAxeViolationsWithOrWithoutDifferences() {
        String withDifferences = ReconcileReport.html(42, ReconcileReportTest.tally(), true, false);
        String without = ReconcileReport.html(
                43,
                new Reconciliation(43, new SecretKeySpec(ExtractJobSupport.HASH_KEY, "HmacSHA256"), Instant.EPOCH),
                false,
                false);
        for (String html : List.of(withDifferences, without)) {
            assertAccessible(html, "Reconciliation of import run");
        }
    }

    @Test
    void theDryRunPreviewHasNoAxeViolationsWithOrWithoutIssues() {
        assertAccessible(
                DryRunReport.html(42, DryRunReportTest.FIGURES, DryRunReportTest.COUNTS, DryRunReportTest.ISSUES, 1201),
                "Dry run of");
        assertAccessible(DryRunReport.html(43, List.of(), List.of(), List.of(), 0), "Dry run of");
    }

    private static void assertAccessible(String html, String title) {
        try (Page page = Objects.requireNonNull(browser).newPage()) {
            List<String> console = new ArrayList<>();
            page.onConsoleMessage(message -> console.add(message.text()));
            page.setContent(html);

            assertThat(page.title()).startsWith(title);
            assertThat(new ShellPage(page).heading()).startsWith(title);
            assertThat(page.locator("script").count()).isZero();
            assertThat(new ShellPage(page).axeViolations()).isEmpty();
            assertThat(console).isEmpty();
        }
    }
}
