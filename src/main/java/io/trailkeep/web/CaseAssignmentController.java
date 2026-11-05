// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseAssignmentService;
import io.trailkeep.casefile.CaseAssignmentService.Reassignment;
import io.trailkeep.casefile.CaseException;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.user.UserDirectoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Reassign, Mark restricted and Mark standard (BUILD_PLAN §4.3). The case page offers each in a native dialog; the
 * GET pages are the same forms without JavaScript, and the pages a refused save re-renders: 422 for an owner or team
 * the form could not have offered, 409 for a stale version with Reload. {@link CaseAssignmentService} decides 404 and
 * 403 first, before any input is read. Parameters are parsed leniently, so a malformed one is a 422 after those
 * checks, never a 400 before them.
 */
@Controller
class CaseAssignmentController {

    private static final String REASSIGN = "casefile/reassign";
    private static final String SENSITIVITY = "casefile/sensitivity";

    private final CaseAssignmentService assignments;
    private final UserDirectoryService directory;

    CaseAssignmentController(CaseAssignmentService assignments, UserDirectoryService directory) {
        this.assignments = assignments;
        this.directory = directory;
    }

    @GetMapping("/cases/{number}/reassign")
    String reassignForm(
            @AuthenticationPrincipal TrailkeepOidcUser principal, @PathVariable String number, Model model) {
        Reassignment reassign = assignments.reassignment(actor(principal), number);
        return reassignPage(reassign, reassign.teamId(), reassign.ownerId(), null, model);
    }

    @PostMapping("/cases/{number}/reassign")
    String reassign(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String number,
            @RequestParam Map<String, String> values,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        Actor actor = actor(principal);
        long teamId = parse(values.get("teamId"));
        long ownerId = parse(values.get("ownerId"));
        try {
            if (!assignments.reassign(actor, number, parse(values.get("version")), teamId, ownerId)) {
                return "redirect:/";
            }
        } catch (CaseException refused) {
            if (refused.kind() != Kind.INVALID && refused.kind() != Kind.CONFLICT) {
                throw refused;
            }
            boolean conflict = refused.kind() == Kind.CONFLICT;
            response.setStatus(conflict ? HttpStatus.CONFLICT.value() : HttpStatus.UNPROCESSABLE_CONTENT.value());
            return reassignPage(assignments.reassignment(actor, number), teamId, ownerId, refused.kind(), model);
        }
        CaseNotice.REASSIGNED.leaveFor(request, CaseNumber.parse(number));
        return "redirect:/cases/" + number;
    }

    @GetMapping("/cases/{number}/{change:restrict|unrestrict}")
    String sensitivityForm(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String number,
            @PathVariable String change,
            Model model) {
        Sensitivity target = target(change);
        model.addAttribute("confirm", assignments.sensitivityChange(actor(principal), number, target));
        model.addAttribute("target", target.name());
        model.addAttribute("conflict", false);
        return SENSITIVITY;
    }

    @PostMapping("/cases/{number}/{change:restrict|unrestrict}")
    String changeSensitivity(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @PathVariable String number,
            @PathVariable String change,
            @RequestParam(required = false) @Nullable String version,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        Actor actor = actor(principal);
        Sensitivity target = target(change);
        try {
            assignments.changeSensitivity(actor, number, parse(version), target);
        } catch (CaseException refused) {
            if (refused.kind() != Kind.CONFLICT) {
                throw refused;
            }
            response.setStatus(HttpStatus.CONFLICT.value());
            model.addAttribute("confirm", assignments.sensitivityChange(actor, number, target));
            model.addAttribute("target", target.name());
            model.addAttribute("conflict", true);
            return SENSITIVITY;
        }
        CaseNotice notice = target == Sensitivity.RESTRICTED ? CaseNotice.RESTRICTED : CaseNotice.UNRESTRICTED;
        notice.leaveFor(request, CaseNumber.parse(number));
        return "redirect:/cases/" + number;
    }

    private static String reassignPage(
            Reassignment reassign, long teamId, long ownerId, @Nullable Kind refused, Model model) {
        model.addAttribute("reassign", reassign);
        model.addAttribute("teamId", teamId);
        model.addAttribute("ownerId", ownerId);
        model.addAttribute("invalid", refused == Kind.INVALID);
        model.addAttribute("conflict", refused == Kind.CONFLICT);
        return REASSIGN;
    }

    /** Which of the two sensitivity paths was asked for; the mapping admits no other. */
    private static Sensitivity target(String change) {
        return "unrestrict".equals(change) ? Sensitivity.STANDARD : Sensitivity.RESTRICTED;
    }

    /**
     * A submitted id or version. Anything unreadable matches no team, user or version, so the service refuses it in
     * its order: an unknown owner is 422 and an unreadable version is stale.
     */
    private static long parse(@Nullable String value) {
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException unreadable) {
            return -1;
        }
    }

    private Actor actor(TrailkeepOidcUser principal) {
        return new Actor(directory.permissionsOf(principal.userId()), principal.displayName());
    }
}
