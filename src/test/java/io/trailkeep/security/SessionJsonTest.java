// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

/** The session's JSON form: what the shared store holds, and that no roles and no arbitrary types get in. */
class SessionJsonTest {

    private final GenericConversionService conversions = SessionJson.conversionService();

    @Test
    void loginSecurityContextRoundTripsWithRealisticIdToken() throws Exception {
        Instant issued = Instant.parse("2026-11-04T02:00:00Z");
        OidcIdToken idToken = OidcIdToken.withTokenValue("eyJ.id.token")
                // Spring's ID-token claim converter turns "iss" into a java.net.URL, so the stored token holds one.
                .claim(
                        "iss",
                        URI.create("http://localhost:8180/realms/trailkeep-dev").toURL())
                .subject("00000000-0000-4000-8000-000000000101")
                .audience(new ArrayList<>(List.of("trailkeep")))
                .issuedAt(issued)
                .expiresAt(issued.plusSeconds(300))
                .claim("auth_time", issued)
                .claim("sid", "4f8c0b1e-provider-session")
                .claim("email", "compliance.admin@example.test")
                .claim("email_verified", true)
                // Parsed JWT arrays are plain ArrayLists, which Spring Security's allow-list covers.
                .claim("groups", new ArrayList<>(List.of("trailkeep-compliance-admins")))
                .build();
        TrailkeepOidcUser principal = new TrailkeepOidcUser(42L, "Compliance Admin", List.of(), idToken, null);
        SecurityContext context = new SecurityContextImpl(new OAuth2AuthenticationToken(principal, List.of(), "idp"));

        byte[] json = Objects.requireNonNull(conversions.convert(context, byte[].class));
        SecurityContext back = (SecurityContext) Objects.requireNonNull(conversions.convert(json, Object.class));

        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) Objects.requireNonNull(back.getAuthentication());
        TrailkeepOidcUser user = (TrailkeepOidcUser) token.getPrincipal();
        assertThat(user.userId()).isEqualTo(42L);
        assertThat(user.getName()).isEqualTo("42");
        assertThat(user.displayName()).isEqualTo("Compliance Admin");
        assertThat(user.getIdToken().getTokenValue()).isEqualTo("eyJ.id.token");
        assertThat(user.getIdToken().getClaimAsString("sid")).isEqualTo("4f8c0b1e-provider-session");
        assertThat(token.getAuthorities()).isEmpty();
        assertThat(token.getAuthorizedClientRegistrationId()).isEqualTo("idp");
        assertThat(new String(json, java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("ROLE_")
                .doesNotContain("java.io.Serializable");
    }

    @Test
    void springSecuritySessionAttributesRoundTrip() {
        DefaultCsrfToken csrf = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token-value");

        Object back = conversions.convert(conversions.convert(csrf, byte[].class), Object.class);

        assertThat(back).isInstanceOf(DefaultCsrfToken.class);
        assertThat(((DefaultCsrfToken) Objects.requireNonNull(back)).getToken()).isEqualTo("token-value");
        assertThat(conversions.convert(conversions.convert(1_700_000_000_000L, byte[].class), Object.class))
                .isEqualTo(1_700_000_000_000L);
        // The case page's one-shot notice (WP-12) is a plain string, read back without a type id.
        assertThat(conversions.convert(conversions.convert("CASE-2026-000001 SAVED", byte[].class), Object.class))
                .isEqualTo("CASE-2026-000001 SAVED");
    }

    @Test
    void typeOutsideTheAllowListIsNotReadBack() {
        byte[] planted =
                "[\"java.util.concurrent.atomic.AtomicLong\",1]".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThatExceptionOfType(ConversionFailedException.class)
                .isThrownBy(() -> conversions.convert(planted, Object.class));
    }
}
