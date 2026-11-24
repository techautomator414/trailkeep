// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.notification.OutboxResendService;
import io.trailkeep.notification.OutboxResendService.Failed;
import io.trailkeep.notification.OutboxResendService.Result;
import io.trailkeep.security.CasePermissionService;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.DomainException;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.UserDirectoryService;
import io.trailkeep.user.UserPermissions;
import java.time.ZoneId;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Failed mail for Compliance Admins (#318, RUNBOOK §8.2): {@code GET /admin/outbox} lists the {@code FAILED} outbox
 * rows with a masked recipient, and {@code POST /admin/outbox/requeue}, with CSRF, puts the chosen ones back to {@code
 * PENDING} and comes back with what it did. Only the security module's audit scope opens it: anyone else, a System
 * Admin included, gets 403, as on the audit screen. {@link OutboxResendService} audits each move.
 */
@Controller
class OutboxResendController {

    private final OutboxResendService resend;
    private final CasePermissionService permissions;
    private final UserDirectoryService directory;
    private final ZoneId zone;

    OutboxResendController(
            OutboxResendService resend,
            CasePermissionService permissions,
            UserDirectoryService directory,
            AppProperties app) {
        this.resend = resend;
        this.permissions = permissions;
        this.directory = directory;
        this.zone = app.timezone();
    }

    @GetMapping("/admin/outbox")
    String list(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @RequestParam(name = "requeued", required = false) Integer requeued,
            @RequestParam(name = "skipped", required = false) Integer skipped,
            Model model) {
        UserPermissions actor = directory.permissionsOf(principal.userId());
        Failed failed = resend.failed(actor, scope(actor));
        model.addAttribute("failed", failed);
        model.addAttribute("zone", zone);
        model.addAttribute("maxBatch", OutboxResendService.MAX_BATCH);
        model.addAttribute("requeued", requeued);
        model.addAttribute("skipped", skipped);
        return "web/outbox";
    }

    @PostMapping("/admin/outbox/requeue")
    String requeue(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @RequestParam(name = "id", required = false) List<Long> ids) {
        UserPermissions actor = directory.permissionsOf(principal.userId());
        Result result = resend.requeue(actor, principal.displayName(), scope(actor), ids == null ? List.of() : ids);
        return "redirect:/admin/outbox?requeued=" + result.requeued() + "&skipped=" + result.skipped();
    }

    private CaseScope scope(UserPermissions actor) {
        return permissions.auditScope(actor).orElseThrow(Forbidden::new);
    }

    /** Anyone without the audit scope. */
    static final class Forbidden extends DomainException {

        private static final long serialVersionUID = 1L;

        Forbidden() {
            super(Kind.FORBIDDEN, "failed mail is for Compliance Admins");
        }
    }
}
