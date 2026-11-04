// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import io.trailkeep.user.UserDirectoryService;
import io.trailkeep.user.UserPermissions;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs after the security context is loaded from the shared session (ADR-0008, IMPLEMENTATION_PLAN A.1 #9):
 * ends a session past its absolute lifetime ({@value #LOGIN_AT_ATTRIBUTE} is set at login), ends the session of a
 * disabled user, and otherwise replaces the stored authentication, which carries no authorities, with one whose
 * authorities and {@link UserPermissions} details come from the database on this request. The rebuilt authentication
 * is never saved, so a role change or a disable takes effect on the next request and no authority is persisted.
 */
final class SessionPermissionsFilter extends OncePerRequestFilter {

    /** Session attribute: login time in epoch milliseconds. */
    static final String LOGIN_AT_ATTRIBUTE = "trailkeep.loginAt";

    private static final Logger LOG = LoggerFactory.getLogger(SessionPermissionsFilter.class);

    private final UserDirectoryService directory;
    private final InstantSource clock;
    private final Duration absoluteLifetime;

    SessionPermissionsFilter(UserDirectoryService directory, InstantSource clock, Duration absoluteLifetime) {
        this.directory = directory;
        this.clock = clock;
        this.absoluteLifetime = absoluteLifetime;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (session != null
                && session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) != null
                && authentication instanceof OAuth2AuthenticationToken token
                && token.getPrincipal() instanceof TrailkeepOidcUser user) {
            rebuild(session, token, user);
        }
        chain.doFilter(request, response);
    }

    private void rebuild(HttpSession session, OAuth2AuthenticationToken token, TrailkeepOidcUser user) {
        Object loginAt = session.getAttribute(LOGIN_AT_ATTRIBUTE);
        if (!(loginAt instanceof Long millis) || clock.millis() - millis > absoluteLifetime.toMillis()) {
            LOG.info("Session ended for user {}: absolute lifetime reached", user.userId());
            end(session);
            return;
        }
        UserPermissions permissions = directory.permissionsOf(user.userId());
        if (permissions.disabled()) {
            LOG.info("Session ended for user {}: user disabled", user.userId());
            end(session);
            return;
        }
        List<GrantedAuthority> authorities = permissions.globalRoles().stream()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        OAuth2AuthenticationToken rebuilt =
                new OAuth2AuthenticationToken(user, authorities, token.getAuthorizedClientRegistrationId());
        rebuilt.setDetails(permissions);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(rebuilt);
        SecurityContextHolder.setContext(context);
    }

    private static void end(HttpSession session) {
        session.invalidate();
        SecurityContextHolder.clearContext();
    }
}
