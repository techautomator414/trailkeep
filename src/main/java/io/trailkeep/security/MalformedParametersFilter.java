// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.apache.tomcat.util.http.fileupload.impl.FileCountLimitExceededException;
import org.apache.tomcat.util.http.fileupload.impl.SizeException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

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
 *
 * <p>The same net catches a malformed or truncated multipart body (issue 234). Tomcat answers that with a 400 only
 * when the first read of the parameters is a filter's (a CSRF token in a form part); when the token comes in the
 * {@code X-CSRF-TOKEN} header, as htmx and scanners send it, the first read is the dispatcher's, Spring raises a
 * {@link MultipartException} that nothing maps, and it was a 500 with an ERROR stack trace. It belongs here, not in an
 * {@code @ExceptionHandler}: an advice for {@code MultipartException} would also take {@code
 * MaxUploadSizeExceededException}, its subclass, and turn the 413 of an oversized part into a 400, and it would not see
 * a failure in a filter. The size and part-count limits are excluded explicitly (see {@code isBadRequest}).
 */
public final class MalformedParametersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (InvalidParameterException malformed) {
            if (!isBadRequest(malformed) || response.isCommitted()) {
                throw malformed;
            }
            refuse(response);
        } catch (ServletException failed) {
            if (!isBadRequest(failed) || response.isCommitted()) {
                throw failed;
            }
            refuse(request, response, failed);
        }
    }

    private static void refuse(HttpServletResponse response) throws IOException {
        response.resetBuffer();
        response.sendError(HttpServletResponse.SC_BAD_REQUEST);
    }

    /**
     * As {@link #refuse(HttpServletResponse)}, for a failed dispatch. The container renders its 400 by dispatching
     * to the error page through the {@code DispatcherServlet} again, and a multipart request whose body cannot be
     * parsed would fail to parse there too, logging an ERROR and losing the page. Spring skips the second parse of
     * a request whose error attribute holds the {@code MultipartException}, so the failure is recorded there, as the
     * container itself does when an exception reaches it.
     */
    private static void refuse(HttpServletRequest request, HttpServletResponse response, Throwable failure)
            throws IOException {
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, failure);
        refuse(response);
    }

    /**
     * Whether the failure is the client's malformed input: an {@link InvalidParameterException} that Tomcat itself
     * classes as a 400, or a {@link MultipartException} (a body that stops before its closing boundary, a part header
     * cut off, a missing boundary) that is not a limit. The limits are not malformed input and must go on as before:
     * {@link InvalidParameterException} also carries the 413 of an upload part over the size limit, and a
     * {@link MaxUploadSizeExceededException} (which is a {@code MultipartException}) or Tomcat's size and part-count
     * exceptions in the cause chain are answered through {@code UploadTooLargeErrorView} or by the container.
     */
    private static boolean isBadRequest(Throwable failure) {
        boolean multipart = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidParameterException invalid) {
                return invalid.getErrorCode() == HttpServletResponse.SC_BAD_REQUEST;
            }
            if (cause instanceof MaxUploadSizeExceededException
                    || cause instanceof SizeException
                    || cause instanceof FileCountLimitExceededException) {
                return false;
            }
            multipart |= cause instanceof MultipartException;
        }
        return multipart;
    }
}
