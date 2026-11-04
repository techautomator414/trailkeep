// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Answers htmx requests that Spring Security rejects with {@code HX-Redirect} instead of a 302 to the login, which
 * htmx would otherwise follow and swap the provider's login page into a fragment (IMPLEMENTATION_PLAN A.2 #23).
 */
final class HtmxAwareResponses {

    /** Matches requests htmx sent ({@code HX-Request: true}). */
    static final RequestMatcher HTMX = HtmxAwareResponses::isHtmx;

    private HtmxAwareResponses() {}

    static boolean isHtmx(HttpServletRequest request) {
        return "true".equals(request.getHeader("HX-Request"));
    }

    /**
     * For htmx: 401 + {@code HX-Redirect} to the application root, which starts the OIDC login as a full page.
     * Otherwise: the usual 302 to {@code loginStart}.
     */
    static AuthenticationEntryPoint entryPoint(String loginStart) {
        AuthenticationEntryPoint fullPage = new LoginUrlAuthenticationEntryPoint(loginStart);
        return (request, response, exception) -> {
            if (HTMX.matches(request)) {
                redirect(request, response, HttpServletResponse.SC_UNAUTHORIZED, "/");
            } else {
                fullPage.commence(request, response, exception);
            }
        };
    }

    /**
     * For htmx: 403 + {@code HX-Redirect} reloading the page the user is on (a stale CSRF token is the usual cause).
     * Otherwise: Spring Security's default 403.
     */
    static AccessDeniedHandler accessDenied() {
        AccessDeniedHandler fullPage = new AccessDeniedHandlerImpl();
        return (request, response, exception) -> {
            if (HTMX.matches(request)) {
                redirect(request, response, HttpServletResponse.SC_FORBIDDEN, currentPath(request));
            } else {
                fullPage.handle(request, response, exception);
            }
        };
    }

    private static void redirect(HttpServletRequest request, HttpServletResponse response, int status, String path) {
        response.setStatus(status);
        response.setHeader("HX-Redirect", request.getContextPath() + path);
    }

    /** The path of {@code HX-Current-URL}; only the path is kept, so the redirect can never leave this origin. */
    static String currentPath(HttpServletRequest request) {
        String current = request.getHeader("HX-Current-URL");
        if (current != null) {
            try {
                String path = URI.create(current).getRawPath();
                if (path != null && path.startsWith("/") && !path.startsWith("//")) {
                    return path;
                }
            } catch (IllegalArgumentException ignored) {
                // Malformed header: fall back to the root.
            }
        }
        return "/";
    }
}
