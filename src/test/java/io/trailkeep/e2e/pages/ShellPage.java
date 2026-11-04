// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e.pages;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Page;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Any page rendered in the application layout, with the axe-core scan used by every accessibility test. */
public final class ShellPage {

    private static final String AXE_SOURCE = loadAxe();

    /** WCAG 2.x A and AA rules (BUILD_PLAN §10.2: 0 violations). */
    private static final String AXE_RUN = """
            async () => {
              const result = await axe.run(document, { runOnly: { type: 'tag',
                values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22a', 'wcag22aa'] } });
              return JSON.stringify(result.violations.map(v =>
                v.id + ' (' + v.impact + '): ' + v.nodes.map(n => n.target.join(' ')).join(', ')));
            }""";

    private final Page page;

    /**
     * Wraps the page.
     *
     * @param page the browser page
     */
    public ShellPage(Page page) {
        this.page = page;
    }

    /**
     * Runs axe-core in the page. It is evaluated through the DevTools protocol, which the page's CSP does not
     * govern, so the policy under test stays exactly as served.
     *
     * @return one line per violation; empty when the page conforms
     */
    public List<String> axeViolations() {
        page.evaluate(AXE_SOURCE);
        JsonArray violations =
                JsonParser.parseString((String) page.evaluate(AXE_RUN)).getAsJsonArray();
        List<String> lines = new ArrayList<>();
        violations.forEach(element -> lines.add(element.getAsString()));
        return lines;
    }

    /**
     * The first heading level 1.
     *
     * @return its text
     */
    public String heading() {
        return String.valueOf(page.textContent("h1")).strip();
    }

    /**
     * Presses Tab once from the top of the page.
     *
     * @return the class attribute of the element that received focus
     */
    public String firstTabStopClass() {
        page.evaluate("() => document.activeElement && document.activeElement.blur()");
        page.keyboard().press("Tab");
        return String.valueOf(page.evaluate("() => document.activeElement.className"));
    }

    /** Opens the session-expiry dialog as the timer would. */
    public void openSessionDialog() {
        page.evaluate("() => window.trailkeep.dialogs.open(document.getElementById('tk-session-dialog'))");
    }

    private static String loadAxe() {
        try (InputStream in = ShellPage.class.getResourceAsStream("/axe/axe.min.js")) {
            if (in == null) {
                throw new IllegalStateException("axe-core missing from test resources");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
