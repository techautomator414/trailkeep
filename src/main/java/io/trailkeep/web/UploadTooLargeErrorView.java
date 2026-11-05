// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.document.AttachmentRejectedException;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.DomainException;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.webmvc.autoconfigure.error.DefaultErrorViewResolver;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.ModelAndView;

/**
 * The error view for a file over {@code spring.servlet.multipart.max-file-size} (#58 review). Tomcat refuses the part
 * while the first filter reads a parameter, or Spring's multipart resolver does, before any controller runs; either
 * way the container dispatches its 413 to the error page. For {@code POST /cases/{number}/attachments} this answers
 * as the controller would for a file the intake refused: 404 for an unreadable case, 403 if the matrix denies
 * {@code COMMENT}, otherwise the case page with 422 and the "too large" summary. Every other error keeps Spring Boot's
 * conventional view ({@link DefaultErrorViewResolver}, which a resolver bean of ours would otherwise replace).
 */
@Component
final class UploadTooLargeErrorView implements ErrorViewResolver {

    /** The one multipart endpoint; its case number is the path segment. */
    private static final Pattern UPLOAD = Pattern.compile("/cases/([^/]+)/attachments");

    private final DefaultErrorViewResolver conventions;
    private final CasePage casePage;
    private final CaseService cases;

    UploadTooLargeErrorView(
            ApplicationContext context, ObjectProvider<WebProperties> web, CasePage casePage, CaseService cases) {
        // A context without a web server (a NONE test, the purge profile) has no WebProperties and never renders.
        this.conventions = new DefaultErrorViewResolver(
                context, web.getIfAvailable(WebProperties::new).getResources());
        this.casePage = casePage;
        this.cases = cases;
    }

    @Override
    public @Nullable ModelAndView resolveErrorView(
            HttpServletRequest request, HttpStatus status, Map<String, Object> model) {
        Matcher upload = UPLOAD.matcher(String.valueOf(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)));
        Authentication signedIn = SecurityContextHolder.getContext().getAuthentication();
        if (status != HttpStatus.CONTENT_TOO_LARGE
                || !upload.matches()
                || signedIn == null
                || !(signedIn.getPrincipal() instanceof TrailkeepOidcUser principal)) {
            return conventions.resolveErrorView(request, status, model);
        }
        String number = upload.group(1);
        ExtendedModelMap page = new ExtendedModelMap();
        try {
            Actor actor = casePage.actor(principal);
            cases.attachableCaseId(actor, number);
            page.addAttribute("uploadError", AttachmentRejectedException.Reason.TOO_LARGE.name());
            ModelAndView view = new ModelAndView(casePage.render(actor, number, request, null, page), page);
            view.setStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            return view;
        } catch (DomainException refused) {
            HttpStatus refusal =
                    refused.kind() == DomainException.Kind.NOT_FOUND ? HttpStatus.NOT_FOUND : HttpStatus.FORBIDDEN;
            ModelAndView view = conventions.resolveErrorView(request, refusal, model);
            if (view != null) {
                view.setStatus(refusal);
            }
            return view;
        }
    }
}
