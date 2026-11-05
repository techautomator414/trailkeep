// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.document.AttachmentException;
import io.trailkeep.document.CaseAttachmentService;
import io.trailkeep.document.CaseAttachmentService.Download;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.DomainException.Kind;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Download and Release of a case's attachments (BUILD_PLAN §4.3, §9). The URL names the case and the attachment's id
 * within it; {@link CaseAttachmentService} decides 404 (an unreadable case, or an id not of this case), then 403 (a
 * quarantined file, or a release by anyone but a Compliance Admin), then 422. A download streams after this method
 * returns, from the async executor, in its own read-only transaction; if every download slot is taken it is 503 with
 * {@code Retry-After} ({@link ErrorAdvice}). Release is offered in a native dialog on the case page; the GET page is
 * the same form without JavaScript, and what a refused reason re-renders with 422.
 */
@Controller
class CaseAttachmentController {

    private static final String RELEASE = "document/release";

    private final CaseAttachmentService attachments;
    private final CasePage page;

    CaseAttachmentController(CaseAttachmentService attachments, CasePage page) {
        this.attachments = attachments;
        this.page = page;
    }

    @GetMapping("/cases/{number}/attachments/{id}")
    ResponseEntity<StreamingResponseBody> download(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String number,
            @PathVariable String id) {
        Download download = attachments.download(page.actor(principal), number, parse(id));
        StreamingResponseBody body = download::writeTo;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, AttachmentDisposition.of(download.fileName()))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .contentType(mediaType(download.contentType()))
                .contentLength(download.sizeBytes())
                .body(body);
    }

    @GetMapping("/cases/{number}/attachments/{id}/release")
    String releaseForm(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String number,
            @PathVariable String id,
            Model model) {
        return releasePage(page.actor(principal), number, parse(id), "", false, model);
    }

    @PostMapping("/cases/{number}/attachments/{id}/release")
    String release(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String number,
            @PathVariable String id,
            @RequestParam(defaultValue = "") String reason,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        Actor actor = page.actor(principal);
        long attachmentId = parse(id);
        try {
            if (attachments.release(actor, number, attachmentId, reason)) {
                CaseNotice.RELEASED.leaveFor(request, CaseNumber.parse(number));
            }
        } catch (AttachmentException refused) {
            if (refused.kind() != Kind.INVALID) {
                throw refused;
            }
            // The form again if the reason was the problem; a file that is not quarantined throws from here (422).
            String form = releasePage(actor, number, attachmentId, reason, true, model);
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            return form;
        }
        return "redirect:/cases/" + CaseNumber.parse(number) + "#attachment-" + attachmentId;
    }

    private String releasePage(
            Actor actor, String number, long attachmentId, String reason, boolean invalid, Model model) {
        model.addAttribute("file", attachments.quarantined(actor, number, attachmentId));
        model.addAttribute("number", CaseNumber.parse(number));
        model.addAttribute("reason", reason);
        model.addAttribute("invalid", invalid);
        return RELEASE;
    }

    /** The stored type, detected at upload; anything unparsable is served as opaque bytes. */
    private static MediaType mediaType(String stored) {
        try {
            return MediaType.parseMediaType(stored);
        } catch (InvalidMediaTypeException unparsable) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /** An id from the URL; anything unreadable names no attachment, which is 404 after the case checks. */
    private static long parse(@Nullable String value) {
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException unreadable) {
            return -1;
        }
    }
}
