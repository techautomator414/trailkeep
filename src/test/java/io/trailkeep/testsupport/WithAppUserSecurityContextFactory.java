// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.testsupport;

import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.UserPermissions;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

/** Builds the security context for {@link WithAppUser}, with the same authorities a real login grants. */
public final class WithAppUserSecurityContextFactory implements WithSecurityContextFactory<WithAppUser> {

    /** Matches {@code spring.security.oauth2.client.registration.idp}. */
    static final String REGISTRATION_ID = "idp";

    @Override
    public SecurityContext createSecurityContext(WithAppUser user) {
        Set<GlobalRole> globalRoles = EnumSet.noneOf(GlobalRole.class);
        globalRoles.addAll(Arrays.asList(user.globalRole()));
        String subject = "test-subject-" + user.id();
        UserPermissions permissions = new UserPermissions(
                user.id(), false, globalRoles, user.team() == 0L ? Map.of() : Map.of(user.team(), user.teamRole()));
        List<GrantedAuthority> authorities = globalRoles.stream()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        Instant now = Instant.now();
        OidcIdToken idToken = OidcIdToken.withTokenValue("test-id-token")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("email", "user-" + user.id() + "@example.test")
                .claim("name", user.name())
                .build();
        TrailkeepOidcUser principal = new TrailkeepOidcUser(user.id(), user.name(), List.of(), idToken, null);

        OAuth2AuthenticationToken token = new OAuth2AuthenticationToken(principal, authorities, REGISTRATION_ID);
        token.setDetails(permissions);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(token);
        return context;
    }
}
