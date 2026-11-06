// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e.pages;

import com.microsoft.playwright.Page;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The cases list (WP-15), driven from the keyboard as a person would. */
public final class CasesListPage {

    private final Page page;

    /**
     * Wraps a page showing {@code /cases}.
     *
     * @param page the browser page
     */
    public CasesListPage(Page page) {
        this.page = page;
    }

    /**
     * Types into the search box and presses Enter, then waits until htmx has replaced the results and moved focus.
     *
     * @param text the search text
     */
    public void search(String text) {
        waitForSettle();
        markResultsStale();
        page.locator("#tk-q").focus();
        page.keyboard().type(text);
        page.keyboard().press("Enter");
        waitForFocusOn("tk-results-status");
    }

    /**
     * Presses Tab until a control has focus, at most a few times.
     *
     * @param id the control's id
     * @param presses the most presses allowed
     * @return how many presses it took
     */
    public int tabTo(String id, int presses) {
        waitForSettle();
        for (int i = 1; i <= presses; i++) {
            page.keyboard().press("Tab");
            if (id.equals(focusedId())) {
                return i;
            }
        }
        throw new AssertionError("Tab never reached #" + id + "; focus is on #" + focusedId());
    }

    /**
     * Presses Enter on the focused control and waits until htmx has replaced the results and focus is on an element.
     *
     * @param id where focus should land
     */
    public void pressEnterAndWaitForFocusOn(String id) {
        // Enter on a control swapped in by htmx before it settled would be a plain navigation, not an htmx request.
        waitForSettle();
        markResultsStale();
        page.keyboard().press("Enter");
        waitForFocusOn(id);
    }

    /**
     * Presses a key that moves focus without an htmx request (opening or closing a dialog) and waits for focus.
     *
     * @param key the key, as Playwright names it
     * @param id where focus should land
     */
    public void pressAndWaitForFocusOn(String key, String id) {
        waitForSettle();
        page.keyboard().press(key);
        page.waitForFunction("id => document.activeElement && document.activeElement.id === id", id);
        waitForSettle();
    }

    /**
     * Presses Tab until the focused element has an accessible name given by {@code aria-label}, at most a few times.
     * It first waits until htmx has settled, so the tab order is that of the swapped content. On failure the message
     * lists every element focus visited, which tells a real tab-order change from a timing problem.
     *
     * @param label the {@code aria-label}
     * @param presses the most presses allowed
     */
    public void tabToLabel(String label, int presses) {
        waitForSettle();
        List<String> visited = new ArrayList<>(presses + 1);
        visited.add(focused());
        for (int i = 1; i <= presses; i++) {
            page.keyboard().press("Tab");
            visited.add(focused());
            if (label.equals(page.evaluate("() => document.activeElement.getAttribute('aria-label')"))) {
                return;
            }
        }
        throw new AssertionError("Tab never reached the control labelled " + label + " in " + presses
                + " presses; focus went " + String.join(" -> ", visited));
    }

    /**
     * Opens the filter dialog, closes it and at once moves focus to the first chip, as a fast keyboard user does
     * (Escape, then Tab) before the dialog's {@code close} event has been dispatched, then waits for that event.
     *
     * @return {@code true} if focus is still on the chip, so the close handler did not pull it back to "Filters"
     */
    public boolean focusMovedOnBeforeTheCloseEventStays() {
        return Boolean.TRUE.equals(page.evaluate("async () => {"
                + " const dialog = document.getElementById('tk-filter-dialog');"
                + " window.trailkeep.dialogs.open(dialog, document.getElementById('tk-filters-open'));"
                + " const chip = document.querySelector('.tk-chips a');"
                + " dialog.close(); chip.focus();"
                + " await new Promise(done => window.setTimeout(done, 100));"
                + " return document.activeElement === chip; }"));
    }

    /** Waits until no htmx request, swap or settle is in progress, so swapped content has its hx-* bound. */
    public void waitForSettle() {
        page.waitForFunction("() => !document.querySelector('.htmx-request, .htmx-swapping, .htmx-settling')");
    }

    /** The focused element, described for a diagnostic: tag, id, aria-label and text. */
    private String focused() {
        return String.valueOf(page.evaluate("() => { const e = document.activeElement; if (!e) { return 'none'; }"
                + " return e.tagName.toLowerCase() + (e.id ? '#' + e.id : '')"
                + " + (e.getAttribute('aria-label') ? '[' + e.getAttribute('aria-label') + ']' : '')"
                + " + ' \"' + (e.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 40) + '\"'; }"));
    }

    /**
     * Whether the filter dialog is open as a modal dialog.
     *
     * @return {@code true} while it is shown modal
     */
    public boolean filtersShownModal() {
        return Boolean.TRUE.equals(
                page.evaluate("() => document.getElementById('tk-filter-dialog').matches(':modal')"));
    }

    /**
     * Whether the filter dialog is open at all.
     *
     * @return {@code true} while it is open, modal or not
     */
    public boolean filtersOpen() {
        return Boolean.TRUE.equals(page.evaluate("() => document.getElementById('tk-filter-dialog').open"));
    }

    /**
     * The accessible names of the active-filter chips.
     *
     * @return one {@code aria-label} per chip, in order
     */
    public List<String> chips() {
        return lines(page.evaluate("() => Array.from(document.querySelectorAll('.tk-chips a'))"
                + ".map(a => a.getAttribute('aria-label')).join('\\n')"));
    }

    /**
     * The value of a form control.
     *
     * @param id the control's id
     * @return its value
     */
    public String value(String id) {
        return String.valueOf(page.evaluate("id => document.getElementById(id).value", id));
    }

    /**
     * The element with focus.
     *
     * @return its id, or an empty string
     */
    public String focusedId() {
        return String.valueOf(page.evaluate("() => document.activeElement ? document.activeElement.id : ''"));
    }

    /**
     * The status line above the table.
     *
     * @return its text
     */
    public String status() {
        return String.valueOf(page.textContent("#tk-results-status")).strip();
    }

    /**
     * The {@code aria-sort} of each header that has one.
     *
     * @return {@code "column text=state"} per sorted header
     */
    public List<String> sortStates() {
        return lines(page.evaluate("() => Array.from(document.querySelectorAll('th[aria-sort]'))"
                + ".map(th => th.textContent.replace(/[▲▼]/g, '').trim() + '=' + th.getAttribute('aria-sort'))"
                + ".join('\\n')"));
    }

    /**
     * The case numbers listed, in order.
     *
     * @return the numbers
     */
    public List<String> numbers() {
        return lines(page.evaluate("() => Array.from(document.querySelectorAll('tbody th[scope=row]'))"
                + ".map(th => th.textContent.trim()).join('\\n')"));
    }

    private static List<String> lines(@Nullable Object joined) {
        return String.valueOf(joined).lines().filter(line -> !line.isBlank()).toList();
    }

    /** Marks the shown results, so a wait can tell that htmx has replaced them. */
    private void markResultsStale() {
        page.evaluate("() => document.getElementById('tk-results-status').setAttribute('data-test-stale', '')");
    }

    private void waitForFocusOn(String id) {
        page.waitForFunction(
                // htmx binds hx-get on swapped content only once it has settled; until then Enter navigates plainly.
                "id => !document.querySelector('[data-test-stale], .htmx-request, .htmx-swapping, .htmx-settling')"
                        + " && document.activeElement && document.activeElement.id === id",
                id);
    }
}
