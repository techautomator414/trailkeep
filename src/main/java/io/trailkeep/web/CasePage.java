// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseAssignmentService;
import io.trailkeep.casefile.CaseCommentService;
import io.trailkeep.casefile.CaseCommentService.CommentView;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.CaseView;
import io.trailkeep.document.CaseAttachmentService;
import io.trailkeep.security.TrailkeepOidcUser;
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
    private final UserDirectoryService directory;

    CasePage(
            CaseService cases,
            CaseAssignmentService assignments,
            CaseCommentService comments,
            CaseAttachmentService attachments,
            UserDirectoryService directory) {
        this.cases = cases;
        this.assignments = assignments;
        this.comments = comments;
        this.attachments = attachments;
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
        CaseView view = cases.view(actor, number);
        model.addAttribute("c", view);
        model.addAttribute("reassign", view.reassignable() ? assignments.reassignment(actor, number) : null);
        List<CommentView> all = comments.comments(actor, number);
        model.addAttribute("comments", all);
        model.addAttribute("pinned", all.stream().filter(CommentView::pinned).toList());
        model.addAttribute("comment", rejected == null ? "" : rejected);
        model.addAttribute("errors", rejected == null ? List.of() : List.of("body"));
        model.addAttribute("attachments", attachments.attachments(actor, number));
        model.addAttribute("limits", attachments.limits());
        // Taken only once the case has been read, so a page that answers 404 never consumes a notice.
        model.addAttribute("notice", CaseNotice.take(request, view.number()));
        return "casefile/case";
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
