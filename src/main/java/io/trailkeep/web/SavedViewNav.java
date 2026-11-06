// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.SavedViewService;
import io.trailkeep.casefile.SavedViewService.View;
import io.trailkeep.security.TrailkeepOidcUser;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The signed-in person's saved views for the layout's navigation: {@code ${@savedViewNav.mine()}}, the first {@link
 * #LIMIT} by name, under "Cases". Anonymous pages, and error pages that cannot reach the database, show none.
 */
@Component("savedViewNav")
class SavedViewNav {

    /** How many views the navigation lists; the rest are on {@code /cases/views}. */
    static final int LIMIT = 20;

    private final SavedViewService views;

    SavedViewNav(SavedViewService views) {
        this.views = views;
    }

    /**
     * The views to list.
     *
     * @return at most {@link #LIMIT} views by name; empty when nobody is signed in
     */
    public List<View> mine() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof TrailkeepOidcUser user)) {
            return List.of();
        }
        try {
            return views.views(user.userId()).stream().limit(LIMIT).toList();
        } catch (DataAccessException unavailable) {
            return List.of();
        }
    }
}
