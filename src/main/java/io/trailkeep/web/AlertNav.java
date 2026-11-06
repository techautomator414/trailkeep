// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.notification.UserAlertService;
import io.trailkeep.security.TrailkeepOidcUser;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The signed-in person's unread alert count for the layout's "Alerts" badge: {@code ${@alertNav.unread()}}. Anonymous
 * pages, and error pages that cannot reach the database, show no badge.
 */
@Component("alertNav")
class AlertNav {

    private final UserAlertService alerts;

    AlertNav(UserAlertService alerts) {
        this.alerts = alerts;
    }

    /**
     * The badge's count.
     *
     * @return the person's unread alerts; 0 when nobody is signed in or the count cannot be read
     */
    public long unread() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof TrailkeepOidcUser user)) {
            return 0;
        }
        try {
            return alerts.unread(user.userId());
        } catch (DataAccessException unavailable) {
            return 0;
        }
    }
}
