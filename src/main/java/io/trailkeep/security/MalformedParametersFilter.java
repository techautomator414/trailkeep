// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers a malformed query or form string with 400 (issue 218). Tomcat refuses a parameter chunk with no name
 * ({@code =x}) or a bad percent-encoding ({@code %zz}) by throwing {@link InvalidParameterException} when the
 * parameters are first read, and it has no setting that ignores them instead. Whoever reads first is anyone: Spring
 * Security's CSRF check, a controller's {@code @RequestParam}, a template's {@code ${param.x}}, the control-character
 * interceptor. Tomcat's own handling of it ends as a 400 only when it escapes a servlet that has not started its
 * response; thrown while a template renders it was a 500 (and a stack trace in the log for every probe).
 *
 * <p>The filter wraps the whole chain, ahead of Spring Security, so it sees the exception wherever it is thrown: raw
 * from a filter, or wrapped by Spring MVC in the {@link ServletException} of a failed dispatch. It does not read
 * parameters itself, so it changes nothing for a request that is well formed or that nobody reads. A response already
 * on its way to the client cannot be changed and the exception goes on as before. Otherwise the buffer is dropped and
 * the container is asked for a 400, which it answers with the application's error page like any other 400 it sends
 * (htmx does not swap a 400 into the page, so an htmx request keeps its page as it does for a 5xx). The exception and
 * its text, which quotes what was sent, are not logged.
 */
public final class MalformedParametersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (InvalidParameterException malformed) {
            if (response.isCommitted()) {
                throw malformed;
            }
            refuse(response);
        } catch (ServletException failed) {
            if (!isMalformed(failed) || response.isCommitted()) {
                throw failed;
            }
            refuse(response);
        }
    }

    private static void refuse(HttpServletResponse response) throws IOException {
        response.resetBuffer();
        response.sendError(HttpServletResponse.SC_BAD_REQUEST);
    }

    private static boolean isMalformed(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidParameterException) {
                return true;
            }
        }
        return false;
    }
}
