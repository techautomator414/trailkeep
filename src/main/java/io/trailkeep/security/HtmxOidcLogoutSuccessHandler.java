// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * RP-initiated logout (ADR-0008): ends the provider session too, using the ID token from the shared principal. For
 * the htmx sign-out the provider's end-session URL is returned as {@code HX-Redirect}, so the browser navigates there
 * instead of following a form redirect that the CSP's {@code form-action 'self'} would block.
 */
final class HtmxOidcLogoutSuccessHandler extends OidcClientInitiatedLogoutSuccessHandler {

    HtmxOidcLogoutSuccessHandler(ClientRegistrationRepository registrations) {
        super(registrations);
        setPostLogoutRedirectUri("{baseUrl}/");
    }

    @Override
    public void onLogoutSuccess(
            HttpServletRequest request, HttpServletResponse response, @Nullable Authentication authentication)
            throws IOException, ServletException {
        if (HtmxAwareResponses.isHtmx(request)) {
            response.setHeader("HX-Redirect", determineTargetUrl(request, response, authentication));
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }
        super.onLogoutSuccess(request, response, authentication);
    }
}
