// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import java.util.List;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

/** {@link UserSessionService} over Spring Session's principal-name index. */
final class JdbcUserSessionService implements UserSessionService {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final JdbcOidcSessionRegistry registry;

    JdbcUserSessionService(
            FindByIndexNameSessionRepository<? extends Session> sessions, JdbcOidcSessionRegistry registry) {
        this.sessions = sessions;
        this.registry = registry;
    }

    @Override
    public int endSessionsOf(long userId) {
        List<String> ids =
                List.copyOf(sessions.findByPrincipalName(Long.toString(userId)).keySet());
        ids.forEach(sessions::deleteById);
        registry.removeSessions(ids);
        return ids.size();
    }
}
