// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e.pages;

import com.microsoft.playwright.Page;
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
        page.keyboard().press(key);
        page.waitForFunction("id => document.activeElement && document.activeElement.id === id", id);
    }

    /**
     * Presses Tab until the focused element has an accessible name given by {@code aria-label}, at most a few times.
     *
     * @param label the {@code aria-label}
     * @param presses the most presses allowed
     */
    public void tabToLabel(String label, int presses) {
        for (int i = 1; i <= presses; i++) {
            page.keyboard().press("Tab");
            if (label.equals(page.evaluate("() => document.activeElement.getAttribute('aria-label')"))) {
                return;
            }
        }
        throw new AssertionError("Tab never reached the control labelled " + label);
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
