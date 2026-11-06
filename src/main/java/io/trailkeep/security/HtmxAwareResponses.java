// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import org.jspecify.annotations.Nullable;
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
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setHeader("HX-Redirect", reloadTarget(request));
            } else {
                fullPage.handle(request, response, exception);
            }
        };
    }

    private static void redirect(HttpServletRequest request, HttpServletResponse response, int status, String path) {
        response.setStatus(status);
        response.setHeader("HX-Redirect", request.getContextPath() + path);
    }

    /**
     * Where an htmx 403 sends the browser: the path of {@code HX-Current-URL}, which already includes the servlet
     * context path, or the context root when the header is missing, malformed or names a page outside the context.
     */
    private static String reloadTarget(HttpServletRequest request) {
        String context = request.getContextPath();
        String path = currentPath(request);
        if (path != null && (context.isEmpty() || path.equals(context) || path.startsWith(context + "/"))) {
            return path;
        }
        return context + "/";
    }

    /** The path of {@code HX-Current-URL}; only the path is kept, so the redirect can never leave this origin. */
    static @Nullable String currentPath(HttpServletRequest request) {
        String current = request.getHeader("HX-Current-URL");
        if (current != null) {
            try {
                String path = URI.create(current).getRawPath();
                if (path != null && path.startsWith("/") && !path.startsWith("//")) {
                    return path;
                }
            } catch (IllegalArgumentException ignored) {
                // Malformed header: the caller falls back to the context root.
            }
        }
        return null;
    }
}
