// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.shared.CaseNumber;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.jspecify.annotations.Nullable;

/**
 * A one-shot confirmation for the case page after a change (the toast). It is kept in the session as a plain
 * string, which the JSON session format reads back without a type id (ADR-0008, so the {@code SessionJson}
 * allow-list is untouched), and is removed when the case page shows it, so reloading or replaying the URL does not
 * show it again. It names its case, so it never appears on another one.
 */
enum CaseNotice {
    /** Edit saved. */
    SAVED,
    /** Owner or team changed. */
    REASSIGNED,
    /** Marked restricted. */
    RESTRICTED,
    /** Marked standard. */
    UNRESTRICTED,
    /** Comment posted. */
    COMMENTED,
    /** Comment pinned. */
    PINNED,
    /** Comment unpinned. */
    UNPINNED;

    private static final String ATTRIBUTE = "tk.caseNotice";

    /**
     * Leaves this notice for the next view of a case.
     *
     * @param request the request that changed the case
     * @param number the case
     */
    void leaveFor(HttpServletRequest request, CaseNumber number) {
        request.getSession().setAttribute(ATTRIBUTE, number + " " + name());
    }

    /**
     * Takes the notice left for a case, if any, removing it from the session.
     *
     * @param request the case page request
     * @param number the case shown
     * @return the notice, or {@code null}
     */
    static @Nullable CaseNotice take(HttpServletRequest request, CaseNumber number) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(ATTRIBUTE) instanceof String notice)) {
            return null;
        }
        String prefix = number + " ";
        if (!notice.startsWith(prefix)) {
            return null;
        }
        session.removeAttribute(ATTRIBUTE);
        for (CaseNotice candidate : values()) {
            if (candidate.name().equals(notice.substring(prefix.length()))) {
                return candidate;
            }
        }
        return null;
    }
}
