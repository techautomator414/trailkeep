// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import io.trailkeep.e2e.pages.ShellPage;
import io.trailkeep.testsupport.NuHtmlChecker;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The HTML validity gate itself (BUILD_PLAN §10 and §12, "HTML validity (Nu validator)"): every accessibility test's
 * {@link ShellPage#axeViolations()} also validates the HTML of the state it checks. Invalid markup fails, a warning
 * does not, an htmx fragment is checked inside a minimal document, and a page whose HTML is not recorded fails instead
 * of passing unvalidated.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
class NuHtmlCheckIT {

    private static final String HEAD =
            "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Synthetic page</title></head>";

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
    void duplicateIdsAndStrayEndTagsFailTheCheck() {
        List<String> nu = nuLines(
                HEAD + "<body><main><h1 id=\"x\">Synthetic</h1><p id=\"x\">Text</p></div></main></body></html>");
        assertThat(nu)
                .anySatisfy(line -> assertThat(line).contains("Duplicate ID “x”"))
                .anySatisfy(line -> assertThat(line).contains("Stray end tag “div”"));
    }

    @Test
    void warningsDoNotFailTheCheck() {
        // A trailing slash on a void element is a warning, not an error.
        assertThat(nuLines(HEAD + "<body><main><h1>Synthetic</h1><p>One<br/>two</p></main></body></html>"))
                .isEmpty();
    }

    @Test
    void anHtmxFragmentIsCheckedInsideAMinimalDocument() {
        assertThat(NuHtmlChecker.check(NuHtmlChecker.wrapFragment("<div id=\"tk-case-results\"><p>None</p></div>")))
                .noneMatch(NuHtmlChecker.Message::error);
        assertThat(NuHtmlChecker.check(NuHtmlChecker.wrapFragment("<li>A list item outside a list</li>")))
                .anyMatch(NuHtmlChecker.Message::error);
    }

    @Test
    void pagesWhoseHtmlIsNotRecordedFailInsteadOfPassingUnvalidated() {
        try (Page page = Objects.requireNonNull(browser).newPage()) {
            page.setContent(HEAD + "<body><main><h1>Synthetic</h1></main></body></html>");
            assertThat(new ShellPage(page).axeViolations())
                    .anySatisfy(line -> assertThat(line).contains("was not opened through ShellPage.recorded(Page)"));
        }
    }

    /** The Nu Html Checker's lines among the violations of a document set as a recorded page's content. */
    private static List<String> nuLines(String html) {
        try (Page page = Objects.requireNonNull(browser).newPage()) {
            ShellPage.setContent(page, html);
            return new ShellPage(page)
                    .axeViolations().stream()
                            .filter(line -> line.startsWith("Nu Html Checker"))
                            .toList();
        }
    }
}
