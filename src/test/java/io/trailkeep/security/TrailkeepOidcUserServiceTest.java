// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.trailkeep.user.ExternalIdentity;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.SignInOutcome;
import io.trailkeep.user.UserAccount;
import io.trailkeep.user.UserSignInService;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class TrailkeepOidcUserServiceTest {

    private static final SecurityProperties PROPERTIES = new SecurityProperties(
            "groups",
            Map.of(
                    "trailkeep-compliance-admins", GlobalRole.COMPLIANCE_ADMIN,
                    "trailkeep-system-admins", GlobalRole.SYSTEM_ADMIN));

    private final UserSignInService signIn = mock(UserSignInService.class);
    private final OidcUserRequest request = mock(OidcUserRequest.class);

    @Test
    void mappedGroupsBecomeGlobalRolesAndUnknownGroupsGrantNothing() {
        OidcUser provider = providerUser(
                "sub-1",
                "Sample Admin",
                "admin@example.test",
                List.of("trailkeep-compliance-admins", "some-other-group"));
        when(signIn.signIn(any(), any()))
                .thenReturn(new SignInOutcome.SignedIn(
                        new UserAccount(7L, "sub-1", "Sample Admin", Set.of(GlobalRole.COMPLIANCE_ADMIN), Map.of())));

        OidcUser user = service(provider).loadUser(request);

        verify(signIn)
                .signIn(
                        new ExternalIdentity("sub-1", "Sample Admin", "admin@example.test"),
                        EnumSet.of(GlobalRole.COMPLIANCE_ADMIN));
        assertThat(user).isInstanceOf(TrailkeepOidcUser.class);
        assertThat(user.getName()).isEqualTo("7");
        assertThat(user.getIdToken()).isSameAs(provider.getIdToken());
        assertThat(user.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_COMPLIANCE_ADMIN");
    }

    @Test
    void theAppliedRolesComeFromTheAccountNotFromTheClaim() {
        // The conflict rule may withhold SYSTEM_ADMIN; the principal must reflect what was applied.
        OidcUser provider = providerUser("sub-2", "Sample", "s@example.test", List.of("trailkeep-system-admins"));
        when(signIn.signIn(any(), any()))
                .thenReturn(new SignInOutcome.SignedIn(new UserAccount(8L, "sub-2", "Sample", Set.of(), Map.of())));

        assertThat(service(provider).loadUser(request).getAuthorities()).isEmpty();
    }

    @Test
    void disabledAccountFailsTheAuthentication() {
        OidcUser provider = providerUser("sub-3", "Gone", "gone@example.test", List.of());
        when(signIn.signIn(any(), any())).thenReturn(new SignInOutcome.Disabled(9L));

        assertThatExceptionOfType(OAuth2AuthenticationException.class)
                .isThrownBy(() -> service(provider).loadUser(request))
                .satisfies(e ->
                        assertThat(e.getError().getErrorCode()).isEqualTo(TrailkeepOidcUserService.ACCOUNT_DISABLED));
    }

    @Test
    void idTokenWithoutEmailIsRefusedBeforeProvisioning() {
        OidcUser provider = providerUser("sub-4", "No Mail", null, List.of());

        assertThatExceptionOfType(OAuth2AuthenticationException.class)
                .isThrownBy(() -> service(provider).loadUser(request))
                .satisfies(e ->
                        assertThat(e.getError().getErrorCode()).isEqualTo(TrailkeepOidcUserService.MISSING_CLAIMS));
        verifyNoInteractions(signIn);
    }

    @Test
    void theDisplayNameFallsBackToTheUserNameThenTheSubject() {
        OidcUser provider = new DefaultOidcUser(
                List.of(),
                OidcIdToken.withTokenValue("t")
                        .subject("sub-5")
                        .issuedAt(Instant.EPOCH)
                        .expiresAt(Instant.EPOCH.plusSeconds(60))
                        .claim("email", "five@example.test")
                        .claim("preferred_username", "five")
                        .build());
        when(signIn.signIn(any(), any()))
                .thenReturn(new SignInOutcome.SignedIn(new UserAccount(5L, "sub-5", "five", Set.of(), Map.of())));

        service(provider).loadUser(request);

        verify(signIn).signIn(new ExternalIdentity("sub-5", "five", "five@example.test"), Set.of());
    }

    private TrailkeepOidcUserService service(OidcUser provider) {
        return new TrailkeepOidcUserService(ignored -> provider, signIn, PROPERTIES);
    }

    private static OidcUser providerUser(String subject, String name, @Nullable String email, List<String> groups) {
        OidcIdToken.Builder token = OidcIdToken.withTokenValue("t")
                .subject(subject)
                .issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(60))
                .claim("name", name)
                .claim("groups", groups);
        if (email != null) {
            token.claim("email", email);
        }
        return new DefaultOidcUser(List.of(), token.build());
    }
}
