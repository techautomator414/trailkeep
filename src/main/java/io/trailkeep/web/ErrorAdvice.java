// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.document.DownloadsBusyException;
import io.trailkeep.shared.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

/**
 * The single error-handling advice (Part B "Web"): maps {@link DomainException} to its status, rendering the
 * {@code problem} fragment for htmx requests (the fragment contract in {@code app.js}) and the error page otherwise.
 * An unreadable case is a 404 exactly like an absent one. A {@link ConstraintViolationException} that reaches the web
 * layer (a controller that skipped form validation before calling a {@code @Validated} service) is a 422, not a 5xx.
 * A request parameter with a control character is a 400 ({@link ControlCharacterInterceptor}).
 * A download refused because every slot is taken is a 503 with {@code Retry-After} (an upload the container refused
 * for its size never reaches here: see {@link UploadTooLargeErrorView}). The exception message never reaches the
 * response.
 */
@ControllerAdvice
class ErrorAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorAdvice.class);

    @ExceptionHandler({
        DomainException.class,
        ConstraintViolationException.class,
        DownloadsBusyException.class,
        ControlCharacterException.class
    })
    ModelAndView domain(Exception exception, HttpServletRequest request, HttpServletResponse response) {
        HttpStatus status = HttpStatus.UNPROCESSABLE_CONTENT;
        if (exception instanceof DomainException domain) {
            status = switch (domain.kind()) {
                case NOT_FOUND -> HttpStatus.NOT_FOUND;
                case FORBIDDEN -> HttpStatus.FORBIDDEN;
                case CONFLICT -> HttpStatus.CONFLICT;
                case INVALID -> HttpStatus.UNPROCESSABLE_CONTENT;
            };
        }
        if (exception instanceof ControlCharacterException) {
            status = HttpStatus.BAD_REQUEST;
        }
        if (exception instanceof DownloadsBusyException) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            response.setHeader(HttpHeaders.RETRY_AFTER, "5");
        }
        LOG.debug("{} -> {}", exception.getClass().getSimpleName(), status.value());
        response.setStatus(status.value());
        boolean htmx = "true".equals(request.getHeader("HX-Request"));
        String view;
        if (htmx) {
            view = "web/fragments/problem :: problem";
        } else if (status.is5xxServerError()) {
            view = "error/5xx";
        } else {
            view = status == HttpStatus.NOT_FOUND ? "error/404" : "error/4xx";
        }
        ModelAndView page = new ModelAndView(view);
        page.addObject("status", status.value());
        return page;
    }
}
