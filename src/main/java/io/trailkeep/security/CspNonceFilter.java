// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sets the exact Content-Security-Policy of BUILD_PLAN §9 with a fresh nonce per request, and exposes the nonce to
 * templates as the request attribute {@value #NONCE_ATTRIBUTE} ({@code th:attr="nonce=${cspNonce}"} on every script
 * tag). Spring Security does not generate nonces, hence this filter (ADR-0009).
 */
public final class CspNonceFilter extends OncePerRequestFilter {

    /** Request attribute holding the nonce. */
    public static final String NONCE_ATTRIBUTE = "cspNonce";

    /** The policy; {@code %s} is the nonce. */
    static final String POLICY = "default-src 'self'; script-src 'self' 'nonce-%s'; style-src 'self'; "
            + "img-src 'self' data:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    private static final int NONCE_BYTES = 16;

    private final SecureRandom random = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] bytes = new byte[NONCE_BYTES];
        random.nextBytes(bytes);
        String nonce = Base64.getEncoder().encodeToString(bytes);
        request.setAttribute(NONCE_ATTRIBUTE, nonce);
        response.setHeader("Content-Security-Policy", POLICY.formatted(nonce));
        chain.doFilter(request, response);
    }
}
