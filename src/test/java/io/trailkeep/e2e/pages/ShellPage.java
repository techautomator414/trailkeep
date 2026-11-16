// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e.pages;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import io.trailkeep.testsupport.NuHtmlChecker;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Any page rendered in the application layout, with the checks every accessibility test runs: axe-core, and the Nu
 * Html Checker on the HTML of the same state ({@link #axeViolations()}).
 */
public final class ShellPage {

    private static final String AXE_SOURCE = loadAxe();

    /** What each page was served, recorded from its creation by {@link #recorded(Page)}. */
    private static final Map<Page, Served> SERVED = Collections.synchronizedMap(new WeakHashMap<>());

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
     * Records, from now on, the HTML the page is served: its documents and its htmx fragments. Every page an
     * accessibility test checks is opened through this method, so {@link #axeViolations()} can validate the HTML of
     * each state it checks.
     *
     * @param page a new page, before its first navigation
     * @return the same page
     */
    public static Page recorded(Page page) {
        Served served = new Served();
        page.onRequestFinished(request -> served.record(page, request));
        SERVED.put(page, served);
        return page;
    }

    /**
     * Sets a page's content to a document that no server serves (a report rendered to a file), and records it as the
     * page's served HTML.
     *
     * @param page the page
     * @param html the document
     */
    public static void setContent(Page page, String html) {
        Served served = SERVED.computeIfAbsent(page, key -> new Served());
        page.setContent(html);
        served.document("the document set as content", () -> html);
    }

    /**
     * Checks the state the page is in.
     *
     * <ul>
     *   <li>axe-core, evaluated through the DevTools protocol, which the page's CSP does not govern, so the policy
     *       under test stays exactly as served;
     *   <li>the Nu Html Checker, on the HTML of the same state: the document as served (once per document), each htmx
     *       fragment received since the last check inside a minimal document, and the live DOM while a dialog is
     *       open. A page that was not opened through {@link #recorded(Page)} fails here, so no state is checked by
     *       axe without its HTML being validated. Nu warnings are printed and do not fail.
     * </ul>
     *
     * @return one line per axe violation and per Nu error; empty when the state conforms
     */
    public List<String> axeViolations() {
        page.evaluate(AXE_SOURCE);
        JsonArray violations =
                JsonParser.parseString((String) page.evaluate(AXE_RUN)).getAsJsonArray();
        List<String> lines = new ArrayList<>();
        violations.forEach(element -> lines.add(element.getAsString()));
        lines.addAll(htmlErrors());
        return lines;
    }

    /** The Nu errors in the HTML of the current state; warnings go to the test output. */
    private List<String> htmlErrors() {
        Served served = SERVED.get(page);
        if (served == null) {
            return List.of("Nu Html Checker: " + page.url() + " was not opened through ShellPage.recorded(Page), so the"
                    + " HTML of the state axe checked is not validated");
        }
        Map<String, String> documents = served.unchecked();
        if (Boolean.TRUE.equals(page.evaluate("() => document.querySelector('dialog[open]') !== null"))) {
            documents.put("the live DOM with a dialog open at " + page.url(), page.content());
        }
        if (documents.isEmpty() && !served.anyChecked()) {
            return List.of("Nu Html Checker: no HTML was recorded for " + page.url());
        }
        List<String> errors = new ArrayList<>();
        documents.forEach((label, html) -> {
            int before = errors.size();
            int warnings = 0;
            for (NuHtmlChecker.Message message : NuHtmlChecker.check(html)) {
                if (message.error()) {
                    errors.add("Nu Html Checker: " + message.describe(label));
                } else {
                    warnings++;
                    System.out.println("[Nu Html Checker] warning: " + message.describe(label));
                }
            }
            System.out.println("[Nu Html Checker] validated " + label + ": " + (errors.size() - before) + " error(s), "
                    + warnings + " warning(s)");
        });
        return errors;
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

    /**
     * Waits until the focused element is the page's error summary. The layout's scripts are deferred, so the summary
     * is in the document a little before {@code app.js} has moved focus to it.
     */
    public void waitForErrorSummaryFocus() {
        page.waitForFunction(
                "() => document.activeElement && document.activeElement.hasAttribute('data-tk-error-summary')");
    }

    /**
     * Waits until the element with this id has focus.
     *
     * @param id the element's id
     */
    public void waitForFocusOn(String id) {
        page.waitForFunction("id => document.activeElement && document.activeElement.id === id", id);
    }

    /**
     * Waits until a closed dialog has given focus back to the control that opened it, then lets the dialog's late
     * {@code close} event run. The event is dispatched as a task after {@code close()}, so a key pressed at once could
     * otherwise race its handler (the race behind #75).
     *
     * @param dialog selector of the dialog
     * @param opener selector of the control that opened it
     */
    public void waitForDialogClosedWithFocusOn(String dialog, String opener) {
        page.waitForFunction(
                "args => !document.querySelector(args[0]).open"
                        + " && document.activeElement === document.querySelector(args[1])",
                List.of(dialog, opener));
        page.evaluate("() => new Promise(done => window.setTimeout(done, 0))");
    }

    /** Opens the session-expiry dialog as the timer would. */
    public void openSessionDialog() {
        page.evaluate("() => window.trailkeep.dialogs.open(document.getElementById('tk-session-dialog'))");
    }

    /**
     * The HTML one page was served: its latest HTML document (a main-frame navigation that is neither a redirect nor
     * a download) and the htmx fragments since the last check. Each body is read when its request finishes, because
     * the browser discards it once the page navigates away.
     */
    private static final class Served {

        private @Nullable String documentLabel;
        private @Nullable Supplier<String> document;
        private boolean documentChecked;
        private boolean anyChecked;
        private final Map<String, Supplier<String>> fragments = new LinkedHashMap<>();

        synchronized void record(Page page, Request request) {
            Response response = request.response();
            if (response == null
                    || !String.valueOf(response.headerValue("content-type")).startsWith("text/html")) {
                return;
            }
            String what = request.method() + " " + response.url();
            if (request.isNavigationRequest() && request.frame().equals(page.mainFrame())) {
                if (response.status() / 100 != 3) {
                    String html = response.text();
                    document("the document served for " + what, () -> html);
                }
            } else if (request.headerValue("hx-request") != null) {
                String html = NuHtmlChecker.wrapFragment(response.text());
                fragments.put("the htmx fragment served for " + what + " #" + fragments.size(), () -> html);
            }
        }

        synchronized void document(String label, Supplier<String> html) {
            documentLabel = label;
            document = html;
            documentChecked = false;
        }

        /** The documents not validated yet, by label; marks them validated. */
        synchronized Map<String, String> unchecked() {
            Map<String, String> documents = new LinkedHashMap<>();
            if (document != null && documentLabel != null && !documentChecked) {
                documents.put(documentLabel, document.get());
                documentChecked = true;
            }
            fragments.forEach((label, html) -> documents.put(label, html.get()));
            fragments.clear();
            anyChecked |= !documents.isEmpty();
            return documents;
        }

        synchronized boolean anyChecked() {
            return anyChecked;
        }
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
