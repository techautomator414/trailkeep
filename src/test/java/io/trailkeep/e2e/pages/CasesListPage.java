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
