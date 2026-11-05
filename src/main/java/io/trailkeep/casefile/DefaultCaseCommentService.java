// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.audit.Redacted;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.security.CaseAction;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.shared.html.MarkdownRenderer;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and posts comments. A comment, its search-text append and its audit event are written in one transaction;
 * the append takes no lock on the case row, so posting never conflicts with an edit (BUILD_PLAN §8 rule 4).
 */
@Service
@Transactional
@CaseAccessPath
class DefaultCaseCommentService implements CaseCommentService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultCaseCommentService.class);

    private final CaseLoader loader;
    private final CaseCommentRepository comments;
    private final CaseScopeService scopes;
    private final CaseSearchWriter search;
    private final MarkdownRenderer markdown;
    private final EntityManager entityManager;
    private final AuditRecorder audit;
    private final InstantSource clock;
    private final ZoneId zone;

    DefaultCaseCommentService(
            CaseLoader loader,
            CaseCommentRepository comments,
            CaseScopeService scopes,
            CaseSearchWriter search,
            MarkdownRenderer markdown,
            EntityManager entityManager,
            AuditRecorder audit,
            InstantSource clock,
            AppProperties app) {
        this.loader = loader;
        this.comments = comments;
        this.scopes = scopes;
        this.search = search;
        this.markdown = markdown;
        this.entityManager = entityManager;
        this.audit = audit;
        this.clock = clock;
        this.zone = app.timezone();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CommentView> comments(Actor actor, String caseNumber) {
        return comments.findForCase(scopes.forUser(actor.permissions()), CaseLoader.parse(caseNumber)).stream()
                .map(comment -> new CommentView(
                        comment.getAuthorName(),
                        LocalDateTime.ofInstant(comment.getCreatedAt(), zone).truncatedTo(ChronoUnit.MINUTES),
                        markdown.render(comment.getBody())))
                .toList();
    }

    @Override
    public void post(Actor actor, String caseNumber, String body) {
        CaseRecord record = loader.load(actor.permissions(), caseNumber, CaseAction.COMMENT);
        // A browser submits a textarea's line breaks as CRLF; counted as one, as the form's maxlength counts them.
        String text = body.replace("\r\n", "\n");
        if (text.isBlank() || text.length() > CaseComment.MAX_BODY) {
            throw new CaseException(Kind.INVALID, "a comment is 1 to 20000 characters");
        }
        long caseId = Objects.requireNonNull(record.getId());
        long actorId = actor.permissions().userId();
        Instant now = clock.instant();
        CaseComment comment = CaseComment.of(caseId, text, actorId, actor.displayName(), now);
        entityManager.persist(comment);
        entityManager.flush();
        search.appendComment(actor.permissions(), record, text, now);
        long commentId = Objects.requireNonNull(comment.getId());
        audit.record(new AuditEntry(
                actorId,
                actor.displayName(),
                AuditOrigin.UI,
                caseId,
                "COMMENT",
                commentId,
                "COMMENT_ADDED",
                audit.diff(null, new PostedComment(text))));
        LOG.info("Comment {} added to case id {}", commentId, caseId);
    }

    /**
     * The audit snapshot of a new comment: its text is possible PHI, so the trail holds only an HMAC and a length.
     *
     * @param body the comment's text
     */
    record PostedComment(@Redacted String body) {}
}
