// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import io.trailkeep.user.UserAccount;
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
 * plus the application account it was provisioned as. The principal's name is the {@code APP_USER.ID}, never an
 * email address.
 */
public final class TrailkeepOidcUser extends DefaultOidcUser {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UserAccount account;

    /**
     * Creates the principal.
     *
     * @param account the provisioned account
     * @param authorities the granted authorities
     * @param idToken the ID token
     * @param userInfo the user-info claims, if fetched
     */
    public TrailkeepOidcUser(
            UserAccount account,
            Collection<? extends GrantedAuthority> authorities,
            OidcIdToken idToken,
            @Nullable OidcUserInfo userInfo) {
        super(authorities, idToken, userInfo);
        this.account = account;
    }

    /**
     * The application account.
     *
     * @return the account
     */
    public UserAccount account() {
        return account;
    }

    @Override
    public String getName() {
        return Long.toString(account.id());
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof TrailkeepOidcUser user && super.equals(user) && account.equals(user.account);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + account.hashCode();
    }
}
