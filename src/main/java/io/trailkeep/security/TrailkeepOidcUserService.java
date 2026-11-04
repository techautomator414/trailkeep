// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import io.trailkeep.user.ExternalIdentity;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.SignInOutcome;
import io.trailkeep.user.UserSignInService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Turns a successful OIDC login into a {@link TrailkeepOidcUser}: maps the group claim to global roles through
 * {@code app.security.group-roles}, provisions the user through {@link UserSignInService}, and refuses a disabled
 * user by failing the authentication, so no authenticated session is ever created for them.
 */
class TrailkeepOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    /** OAuth2 error code for a refused, disabled account. */
    static final String ACCOUNT_DISABLED = "account_disabled";

    /** OAuth2 error code for an ID token without the claims the account needs. */
    static final String MISSING_CLAIMS = "missing_claims";

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final UserSignInService signIn;
    private final SecurityProperties properties;

    TrailkeepOidcUserService(
            OAuth2UserService<OidcUserRequest, OidcUser> delegate,
            UserSignInService signIn,
            SecurityProperties properties) {
        this.delegate = delegate;
        this.signIn = signIn;
        this.properties = properties;
    }

    TrailkeepOidcUserService(UserSignInService signIn, SecurityProperties properties) {
        this(new OidcUserService(), signIn, properties);
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        OidcUser oidcUser = delegate.loadUser(request);
        if (oidcUser == null) {
            throw new OAuth2AuthenticationException(new OAuth2Error(MISSING_CLAIMS, "No OIDC user", null));
        }
        ExternalIdentity identity = identity(oidcUser);
        SignInOutcome outcome = signIn.signIn(identity, claimedRoles(oidcUser));
        return switch (outcome) {
            case SignInOutcome.Disabled disabled ->
                throw new OAuth2AuthenticationException(new OAuth2Error(ACCOUNT_DISABLED, "Account disabled", null));
            case SignInOutcome.SignedIn signedIn -> {
                List<GrantedAuthority> authorities = new ArrayList<>();
                signedIn.account()
                        .globalRoles()
                        .forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name())));
                yield new TrailkeepOidcUser(
                        signedIn.account(), authorities, oidcUser.getIdToken(), oidcUser.getUserInfo());
            }
        };
    }

    private static ExternalIdentity identity(OidcUser user) {
        String subject = user.getSubject();
        String email = user.getEmail();
        if (subject == null || email == null || email.isBlank()) {
            throw new OAuth2AuthenticationException(new OAuth2Error(MISSING_CLAIMS, "sub and email required", null));
        }
        return new ExternalIdentity(
                subject, firstNonBlank(user.getFullName(), user.getPreferredUsername(), subject), email);
    }

    private Set<GlobalRole> claimedRoles(OidcUser user) {
        Set<GlobalRole> roles = EnumSet.noneOf(GlobalRole.class);
        Object claim = user.getClaims().get(properties.groupsClaim());
        if (claim instanceof Collection<?> groups) {
            for (Object group : groups) {
                GlobalRole role = properties.groupRoles().get(String.valueOf(group));
                if (role != null) {
                    roles.add(role);
                }
            }
        }
        return roles;
    }

    private static String firstNonBlank(@Nullable String first, @Nullable String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : fallback;
    }
}
