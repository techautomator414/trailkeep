// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.user.UserPreferenceService;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Whether the signed-in person's single-key shortcuts are on, for the layout's {@code data-tk-shortcuts} attribute:
 * {@code ${@preferenceNav.shortcuts()}}. Anonymous pages, and error pages that cannot reach the database, answer
 * {@code false}, so the shortcuts are inert there.
 */
@Component("preferenceNav")
class PreferenceNav {

    private final UserPreferenceService preferences;

    PreferenceNav(UserPreferenceService preferences) {
        this.preferences = preferences;
    }

    /**
     * Whether {@code /} and {@code N} act on this page.
     *
     * @return {@code true} for a signed-in person who has not turned the shortcuts off
     */
    public boolean shortcuts() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof TrailkeepOidcUser user)) {
            return false;
        }
        try {
            return preferences.shortcutsEnabled(user.userId());
        } catch (DataAccessException unavailable) {
            return false;
        }
    }
}
