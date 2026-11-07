// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseAssignmentService;
import io.trailkeep.casefile.CaseCommentService;
import io.trailkeep.casefile.CaseCommentService.CommentView;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.CaseView;
import io.trailkeep.closure.ClosureService;
import io.trailkeep.closure.ClosureService.PendingClosure;
import io.trailkeep.document.CaseAttachmentService;
import io.trailkeep.security.CaseAction;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.updaterequest.UpdateRequestService;
import io.trailkeep.user.UserDirectoryService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/**
 * Renders the case page (BUILD_PLAN §4.3) for {@link CaseController} and for {@link ErrorAdvice}, which shows it with
 * the "too large" summary when the container refused an upload before any controller ran.
 */
@Component
class CasePage {

    private final CaseService cases;
    private final CaseAssignmentService assignments;
    private final CaseCommentService comments;
    private final CaseAttachmentService attachments;
    private final UpdateRequestService requests;
    private final ClosureService closures;
    private final UserDirectoryService directory;

    CasePage(
            CaseService cases,
            CaseAssignmentService assignments,
            CaseCommentService comments,
            CaseAttachmentService attachments,
            UpdateRequestService requests,
            ClosureService closures,
            UserDirectoryService directory) {
        this.cases = cases;
        this.assignments = assignments;
        this.comments = comments;
        this.attachments = attachments;
        this.requests = requests;
        this.closures = closures;
        this.directory = directory;
    }

    /**
     * Fills the model for the case page; {@code NOT_FOUND} for a case the actor may not read.
     *
     * @param actor the actor
     * @param number the case number as it appears in the URL
     * @param request the request, whose session may hold a notice for this case
     * @param rejected a comment refused as invalid, shown again with the error summary; {@code null} for none
     * @param model the model
     * @return the view name
     */
    String render(Actor actor, String number, HttpServletRequest request, @Nullable String rejected, Model model) {
        return fill(cases.view(actor, number), actor, number, request, rejected, model);
    }

    /**
     * Fills the model for a full view of the case page, which records {@code VIEW_RESTRICTED} if the case is
     * restricted (BUILD_PLAN §3.3); {@code NOT_FOUND} for a case the actor may not read, recording nothing.
     *
     * @param actor the actor
     * @param number the case number as it appears in the URL
     * @param request the request, whose session may hold a notice for this case
     * @param model the model
     * @return the view name
     */
    String open(Actor actor, String number, HttpServletRequest request, Model model) {
        return fill(cases.open(actor, number), actor, number, request, null, model);
    }

    private String fill(
            CaseView view,
            Actor actor,
            String number,
            HttpServletRequest request,
            @Nullable String rejected,
            Model model) {
        model.addAttribute("c", view);
        model.addAttribute("reassign", view.reassignable() ? assignments.reassignment(actor, number) : null);
        List<CommentView> all = comments.comments(actor, number);
        model.addAttribute("comments", all);
        model.addAttribute("pinned", all.stream().filter(CommentView::pinned).toList());
        model.addAttribute("comment", rejected == null ? "" : rejected);
        model.addAttribute("errors", rejected == null ? List.of() : List.of("body"));
        model.addAttribute("attachments", attachments.attachments(actor, number));
        model.addAttribute("limits", attachments.limits());
        // Open update requests, and the Request update dialog's recipients when the actor may ask (WP-18).
        model.addAttribute("requests", requests.openForCase(actor, number));
        boolean requestable = cases.allows(actor, number, CaseAction.REQUEST_UPDATE);
        model.addAttribute("recipients", requestable ? requests.recipients(actor, number) : null);
        closure(actor, number, model);
        // Taken only once the case has been read, so a page that answers 404 never consumes a notice.
        model.addAttribute("notice", CaseNotice.take(request, view.number()));
        return "casefile/case";
    }

    /**
     * WP-19: Request closure's choices when the matrix allows it (an open case); a pending request's banner with
     * Approve and Reject or request changes for its approver; the latest decision (the "Requires Changes" badge on an
     * open case, the locked closure fields on a closed one).
     */
    private void closure(Actor actor, String number, Model model) {
        boolean closable = cases.allows(actor, number, CaseAction.REQUEST_CLOSURE);
        model.addAttribute("closureChoices", closable ? closures.choices(actor, number) : null);
        model.addAttribute("closureForm", ClosureController.ClosureForm.EMPTY);
        PendingClosure pending = closures.pending(actor, number).orElse(null);
        model.addAttribute("pendingClosure", pending);
        model.addAttribute(
                "decides",
                pending != null
                        && pending.approverId() == actor.permissions().userId()
                        && cases.allows(actor, number, CaseAction.DECIDE_CLOSURE));
        model.addAttribute("decisionForm", ClosureDecisionController.DecisionForm.EMPTY);
        model.addAttribute("lastDecision", closures.lastDecision(actor, number).orElse(null));
    }

    /**
     * The signed-in person as the services see them, with permissions rebuilt from the database.
     *
     * @param principal the signed-in user
     * @return the actor
     */
    Actor actor(TrailkeepOidcUser principal) {
        return new Actor(directory.permissionsOf(principal.userId()), principal.displayName());
    }
}
