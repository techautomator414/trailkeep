// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import java.io.Serial;
import java.util.Collection;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * The authenticated principal: the provider's {@link OidcUser} (kept whole, so RP-initiated logout has the ID token)
 * plus the {@code APP_USER.ID} it was provisioned as, which is also its name (never an email address), and the display
 * name provisioned with it. It is stored
 * in the shared session as JSON, so it carries no roles: permissions are rebuilt from the database on every request
 * ({@link SessionPermissionsFilter}, ADR-0008).
 */
public final class TrailkeepOidcUser extends DefaultOidcUser {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long userId;
    private final String displayName;

    /**
     * Creates the principal.
     *
     * @param userId {@code APP_USER.ID}
     * @param displayName {@code APP_USER.DISPLAY_NAME} at login
     * @param authorities the granted authorities; empty for the stored principal
     * @param idToken the ID token
     * @param userInfo the user-info claims, if fetched
     */
    public TrailkeepOidcUser(
            long userId,
            String displayName,
            Collection<? extends GrantedAuthority> authorities,
            OidcIdToken idToken,
            @Nullable OidcUserInfo userInfo) {
        super(authorities, idToken, userInfo);
        this.userId = userId;
        this.displayName = displayName;
    }

    /**
     * The display name provisioned at login.
     *
     * @return the name
     */
    public String displayName() {
        return displayName;
    }

    /**
     * The application user's id.
     *
     * @return {@code APP_USER.ID}
     */
    public long userId() {
        return userId;
    }

    @Override
    public String getName() {
        return Long.toString(userId);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof TrailkeepOidcUser user && super.equals(user) && userId == user.userId;
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + Long.hashCode(userId);
    }
}
