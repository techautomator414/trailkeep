// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserDirectoryService;
import io.trailkeep.user.UserPermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

class SessionPermissionsFilterTest {

    private static final Instant LOGIN = Instant.parse("2026-11-04T08:00:00Z");
    private static final long USER = 7L;

    private final UserDirectoryService directory = mock(UserDirectoryService.class);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void storedAuthenticationIsReplacedByOneBuiltFromTheDatabase() throws Exception {
        UserPermissions permissions =
                new UserPermissions(USER, false, Set.of(GlobalRole.COMPLIANCE_ADMIN), Map.of(3L, TeamRole.MANAGER));
        when(directory.permissionsOf(USER)).thenReturn(permissions);
        MockHttpSession session = signedInSession();

        run(session, LOGIN.plus(Duration.ofHours(1)));

        Authentication rebuilt =
                Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication());
        assertThat(rebuilt.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_COMPLIANCE_ADMIN");
        assertThat(rebuilt.getDetails()).isEqualTo(permissions);
        assertThat(((TrailkeepOidcUser) Objects.requireNonNull(rebuilt.getPrincipal())).userId())
                .isEqualTo(USER);
        assertThat(session.isInvalid()).isFalse();
        // The stored context is untouched: no authority reaches the session.
        Authentication stored = ((SecurityContextImpl) Objects.requireNonNull(
                        session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)))
                .getAuthentication();
        assertThat(Objects.requireNonNull(stored).getAuthorities()).isEmpty();
    }

    @Test
    void disabledUserLosesTheSession() throws Exception {
        when(directory.permissionsOf(USER)).thenReturn(UserPermissions.none(USER));
        MockHttpSession session = signedInSession();

        run(session, LOGIN.plusSeconds(60));

        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void sessionEndsAfterTheAbsoluteLifetimeHoweverActive() throws Exception {
        MockHttpSession session = signedInSession();

        run(session, LOGIN.plus(Duration.ofHours(8)).plusSeconds(1));

        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(directory);
    }

    @Test
    void sessionWithoutLoginTimeIsEnded() throws Exception {
        MockHttpSession session = signedInSession();
        session.removeAttribute(SessionPermissionsFilter.LOGIN_AT_ATTRIBUTE);

        run(session, LOGIN);

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void requestWithoutStoredSessionIsLeftAlone() throws Exception {
        OAuth2AuthenticationToken token = token();
        SecurityContextHolder.getContext().setAuthentication(token);

        new SessionPermissionsFilter(directory, InstantSource.fixed(LOGIN), Duration.ofHours(8))
                .doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(token);
        verifyNoInteractions(directory);
    }

    private void run(MockHttpSession session, Instant now) throws Exception {
        SecurityContextImpl stored = (SecurityContextImpl) Objects.requireNonNull(
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
        SecurityContextHolder.getContext().setAuthentication(stored.getAuthentication());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        new SessionPermissionsFilter(directory, InstantSource.fixed(now), Duration.ofHours(8))
                .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }

    private static MockHttpSession signedInSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(token()));
        session.setAttribute(SessionPermissionsFilter.LOGIN_AT_ATTRIBUTE, LOGIN.toEpochMilli());
        return session;
    }

    private static OAuth2AuthenticationToken token() {
        OidcIdToken idToken = OidcIdToken.withTokenValue("t")
                .subject("sub-7")
                .issuedAt(LOGIN)
                .expiresAt(LOGIN.plusSeconds(300))
                .build();
        return new OAuth2AuthenticationToken(
                new TrailkeepOidcUser(USER, "Sample User", List.of(), idToken, null), List.of(), "idp");
    }
}
