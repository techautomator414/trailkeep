// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.InstantSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

/** Records the login time the absolute session lifetime is measured from, then continues to the saved request. */
final class LoginTimeSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private final InstantSource clock;

    LoginTimeSuccessHandler(InstantSource clock) {
        this.clock = clock;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws ServletException, IOException {
        request.getSession().setAttribute(SessionPermissionsFilter.LOGIN_AT_ATTRIBUTE, clock.millis());
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
