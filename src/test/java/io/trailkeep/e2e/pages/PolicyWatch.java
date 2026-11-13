// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e.pages;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every Content-Security-Policy violation a browser context reports, from two independent sources: the
 * {@code securitypolicyviolation} event of each document (recorded by an init script, which runs through the DevTools
 * protocol and so is not itself governed by the page's policy), and the console's "Refused to …" messages and page
 * errors. Either alone has gaps: the event is lost when a document unloads before it is read, and the console does not
 * name the directive.
 */
public final class PolicyWatch {

    private static final String RECORDER = """
            window.__tkViolations = [];
            document.addEventListener('securitypolicyviolation', e => window.__tkViolations.push(
                e.effectiveDirective + ' blocked ' + (e.blockedURI || '(inline)') + ' at ' + e.sourceFile + ':'
                + e.lineNumber));""";

    private final List<String> console = new CopyOnWriteArrayList<>();

    /**
     * Starts watching a context: every page opened in it from now on records its violations.
     *
     * @param context the browser context
     */
    public PolicyWatch(BrowserContext context) {
        context.addInitScript(RECORDER);
        context.onPage(this::watch);
        context.pages().forEach(this::watch);
    }

    /**
     * The violations reported so far by the page's current document and by the console of any page in the context;
     * the console's list is emptied by reading it.
     *
     * @param page the page whose document is read
     * @return one line per violation; empty when there was none
     */
    public List<String> drain(Page page) {
        // The event is queued as a task after the violation; let it run.
        page.evaluate("() => new Promise(done => window.setTimeout(done, 0))");
        List<String> found = new ArrayList<>();
        Object recorded = page.evaluate("() => (window.__tkViolations || []).splice(0)");
        if (recorded instanceof List<?> lines) {
            lines.forEach(line -> found.add(String.valueOf(line)));
        }
        found.addAll(console);
        console.clear();
        return found;
    }

    private void watch(Page page) {
        page.onConsoleMessage(message -> {
            if (message.text().contains("Content Security Policy")
                    || message.text().contains("Refused to")) {
                console.add(message.text());
            }
        });
        page.onPageError(console::add);
    }
}
