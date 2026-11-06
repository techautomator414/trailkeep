// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseCommentService;
import io.trailkeep.casefile.CaseException;
import io.trailkeep.casefile.CaseFields;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.TitleWarning;
import io.trailkeep.document.AttachmentRejectedException;
import io.trailkeep.document.CaseAttachmentService;
import io.trailkeep.document.ScannerUnavailableException;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.time.InstantSource;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * The one-page create form (BUILD_PLAN §4.2) and the case page (§4.3), which shows a one-shot {@link CaseNotice}. The
 * case number is the only identifier in a URL; a case the person may not read answers 404 like an absent one, decided
 * by {@link CaseService} through the person's scope, which also decides who may create. The form is re-rendered with
 * 422 when a field is invalid or the title trips the PHI heuristic and has not been confirmed. A comment is posted
 * from the case page as a plain form; a blank or too long one re-renders the page with 422, the text kept, after
 * {@link CaseCommentService} has decided 404 and 403. Pin and Unpin are one-button forms per comment, decided in the
 * same order; an id naming no comment of the case is 422. A file is uploaded from the same page as a plain multipart
 * form, decided by {@link CaseAttachmentService} in the same order; a refused file re-renders the page with 422, and
 * an unavailable scanner with 503 and "try again" (nothing is stored either way).
 */
@Controller
class CaseController {

    private static final String FORM = "casefile/new-case";

    /** The form's fields in page order, so the error summary follows the page. */
    private static final List<String> FIELDS = List.of(
            "caseTypeId",
            "title",
            "priority",
            "identifiedOn",
            "teamId",
            "ownerId",
            "subjectText",
            "description",
            "sensitivity");

    private final CaseService cases;
    private final CaseCommentService comments;
    private final CaseAttachmentService attachments;
    private final CasePage page;
    private final InstantSource clock;
    private final AppProperties app;

    CaseController(
            CaseService cases,
            CaseCommentService comments,
            CaseAttachmentService attachments,
            CasePage page,
            InstantSource clock,
            AppProperties app) {
        this.cases = cases;
        this.comments = comments;
        this.attachments = attachments;
        this.page = page;
        this.clock = clock;
        this.app = app;
    }

    @GetMapping("/cases/new")
    String newCase(@AuthenticationPrincipal TrailkeepOidcUser principal, Model model) {
        Map<String, String> values = new HashMap<>();
        values.put("identifiedOn", today().toString());
        values.put("ownerId", Long.toString(principal.userId()));
        values.put("priority", "NORMAL");
        values.put("sensitivity", "STANDARD");
        return form(page.actor(principal), values, List.of(), model);
    }

    @PostMapping("/cases")
    String create(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @Valid CaseFields fields,
            BindingResult binding,
            @RequestParam(defaultValue = "false") boolean titleConfirmed,
            @RequestParam Map<String, String> values,
            HttpServletResponse response,
            Model model) {
        Actor actor = page.actor(principal);
        if (binding.hasErrors()) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            return form(
                    actor,
                    values,
                    FIELDS.stream().filter(binding::hasFieldErrors).toList(),
                    model);
        }
        Set<TitleWarning> warnings = cases.titleWarnings(fields.title());
        if (!warnings.isEmpty() && !titleConfirmed) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("warnings", warnings);
            return form(actor, values, List.of(), model);
        }
        // A query flag, not a flash attribute: flash maps do not survive the JSON session format (ADR-0008).
        return "redirect:/cases/" + cases.create(actor, fields) + "?created";
    }

    @GetMapping("/cases/{number}")
    String casePage(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String number,
            @RequestParam(required = false) @Nullable String created,
            @RequestHeader(name = "HX-Request", required = false) @Nullable String htmx,
            HttpServletRequest request,
            Model model) {
        model.addAttribute("created", created != null);
        // A full page view of a restricted case is audited; an htmx request for part of the page is not a new view.
        if ("true".equals(htmx)) {
            return page.render(page.actor(principal), number, request, null, model);
        }
        return page.open(page.actor(principal), number, request, model);
    }

    @PostMapping("/cases/{number}/comments")
    String comment(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String number,
            @RequestParam(defaultValue = "") String body,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        Actor actor = page.actor(principal);
        try {
            comments.post(actor, number, body);
        } catch (CaseException refused) {
            if (refused.kind() != Kind.INVALID) {
                throw refused;
            }
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            return page.render(actor, number, request, body, model);
        }
        CaseNumber parsed = CaseNumber.parse(number);
        CaseNotice.COMMENTED.leaveFor(request, parsed);
        return "redirect:/cases/" + parsed + "#comments";
    }

    @PostMapping("/cases/{number}/comments/{change:pin|unpin}")
    String pin(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String number,
            @PathVariable String change,
            @RequestParam(required = false) @Nullable String comment,
            HttpServletRequest request) {
        boolean pin = "pin".equals(change);
        long commentId = parse(comment);
        comments.setPinned(page.actor(principal), number, commentId, pin);
        CaseNumber parsed = CaseNumber.parse(number);
        (pin ? CaseNotice.PINNED : CaseNotice.UNPINNED).leaveFor(request, parsed);
        return "redirect:/cases/" + parsed + "#comment-" + commentId;
    }

    @PostMapping("/cases/{number}/attachments")
    String attach(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String number,
            @RequestParam(required = false) @Nullable MultipartFile file,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model)
            throws IOException {
        Actor actor = page.actor(principal);
        String name = file == null ? "" : String.valueOf(file.getOriginalFilename());
        try (InputStream content = file == null ? InputStream.nullInputStream() : file.getInputStream()) {
            CaseNotice notice = switch (attachments.upload(actor, number, name, content)) {
                case CLEAN -> CaseNotice.UPLOADED;
                case QUARANTINED -> CaseNotice.QUARANTINED;
                case NOT_SCANNED -> CaseNotice.UPLOADED_NOT_SCANNED;
                case RELEASED -> throw new IllegalStateException("an upload is never stored released");
            };
            CaseNumber parsed = CaseNumber.parse(number);
            notice.leaveFor(request, parsed);
            return "redirect:/cases/" + parsed + "#attachments";
        } catch (AttachmentRejectedException rejected) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("uploadError", rejected.reason().name());
        } catch (ScannerUnavailableException unavailable) {
            // Not a client error: the service cannot scan now, so nothing was stored and the person may try again.
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            model.addAttribute("uploadError", "SCANNER");
        }
        return page.render(actor, number, request, null, model);
    }

    /** A submitted comment id; anything unreadable names no comment, which the service refuses after 404 and 403. */
    private static long parse(@Nullable String value) {
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException unreadable) {
            return -1;
        }
    }

    private String form(Actor actor, Map<String, String> values, List<String> errors, Model model) {
        model.addAttribute("choices", cases.createChoices(actor));
        model.addAttribute("values", values);
        model.addAttribute("errors", errors);
        model.addAttribute("today", today());
        return FORM;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), app.timezone());
    }
}
