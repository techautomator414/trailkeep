// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.audit.Redacted;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.notification.NotificationService;
import io.trailkeep.security.CaseAction;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.shared.html.MarkdownRenderer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads, posts and pins comments. A comment, its search-text append and its audit event are written in one
 * transaction; the append takes no lock on the case row, so posting never conflicts with an edit (BUILD_PLAN §8 rule
 * 4). A pin is a {@code CASE_COMMENT_PIN} row, inserted or deleted with its audit event; the comment is untouched.
 * A comment by anyone but the case's owner also queues the {@code comment-added} mail to the owner, in the same
 * transaction, and every comment publishes {@link CommentPosted} there for modules that react to it.
 */
@Service
@Transactional
@CaseAccessPath
class DefaultCaseCommentService implements CaseCommentService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultCaseCommentService.class);

    private final CaseLoader loader;
    private final CaseCommentRepository comments;
    private final CaseCommentPinRepository pins;
    private final CaseScopeService scopes;
    private final CaseSearchWriter search;
    private final MarkdownRenderer markdown;
    private final EntityManager entityManager;
    private final AuditRecorder audit;
    private final NotificationService notifications;
    private final ApplicationEventPublisher events;
    private final InstantSource clock;
    private final ZoneId zone;

    DefaultCaseCommentService(
            CaseLoader loader,
            CaseCommentRepository comments,
            CaseCommentPinRepository pins,
            CaseScopeService scopes,
            CaseSearchWriter search,
            MarkdownRenderer markdown,
            EntityManager entityManager,
            AuditRecorder audit,
            NotificationService notifications,
            ApplicationEventPublisher events,
            InstantSource clock,
            AppProperties app) {
        this.loader = loader;
        this.comments = comments;
        this.pins = pins;
        this.scopes = scopes;
        this.search = search;
        this.markdown = markdown;
        this.entityManager = entityManager;
        this.audit = audit;
        this.notifications = notifications;
        this.events = events;
        this.clock = clock;
        this.zone = app.timezone();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CommentView> comments(Actor actor, String caseNumber) {
        CaseScope scope = scopes.forUser(actor.permissions());
        CaseNumber number = CaseLoader.parse(caseNumber);
        Set<Long> pinned = pins.findForCase(scope, number).stream()
                .map(CaseCommentPin::getCommentId)
                .collect(Collectors.toUnmodifiableSet());
        return comments.findForCase(scope, number).stream()
                .map(comment -> {
                    long id = Objects.requireNonNull(comment.getId());
                    return new CommentView(
                            id,
                            comment.getAuthorName(),
                            LocalDateTime.ofInstant(comment.getCreatedAt(), zone)
                                    .truncatedTo(ChronoUnit.MINUTES),
                            markdown.render(comment.getBody()),
                            pinned.contains(id));
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> lastCommentAt(Actor actor, String caseNumber) {
        return comments.findForCase(scopes.forUser(actor.permissions()), CaseLoader.parse(caseNumber)).stream()
                .map(CaseComment::getCreatedAt)
                .max(Comparator.naturalOrder());
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
        events.publishEvent(
                new CommentPosted(caseId, record.getCaseNumber(), commentId, actorId, actor.displayName(), now));
        if (record.getOwnerId() != actorId) {
            // Number, title, who and a link: never the comment, description or subject (BUILD_PLAN §4.7, §9).
            String number = record.getCaseNumber().toString();
            notifications.notifyUser(
                    record.getOwnerId(),
                    "comment-added",
                    Map.of("caseNumber", number, "title", record.getTitle(), "commenter", actor.displayName()),
                    caseId,
                    "/cases/" + number + "#comments");
        }
    }

    @Override
    public void setPinned(Actor actor, String caseNumber, long commentId, boolean pinned) {
        CaseRecord record = loader.load(actor.permissions(), caseNumber, CaseAction.PIN);
        // Pins of a case are serialized on its row: a second request waits, then re-reads the state the first left
        // and, if that is the state asked for, writes nothing (no duplicate key, no stale delete).
        entityManager.lock(record, LockModeType.PESSIMISTIC_WRITE);
        CaseScope scope = scopes.forUser(actor.permissions());
        CaseNumber number = CaseLoader.parse(caseNumber);
        if (comments.findOne(scope, number, commentId).isEmpty()) {
            throw new CaseException(Kind.INVALID, "no such comment on this case");
        }
        Optional<CaseCommentPin> pin = pins.findOne(scope, number, commentId);
        if (pin.isPresent() == pinned) {
            return;
        }
        long caseId = Objects.requireNonNull(record.getId());
        long actorId = actor.permissions().userId();
        if (pinned) {
            entityManager.persist(new CaseCommentPin(commentId, caseId, actorId, clock.instant()));
        } else {
            entityManager.remove(pin.orElseThrow());
        }
        entityManager.flush();
        audit.record(new AuditEntry(
                actorId,
                actor.displayName(),
                AuditOrigin.UI,
                caseId,
                "COMMENT",
                commentId,
                pinned ? "COMMENT_PINNED" : "COMMENT_UNPINNED",
                audit.diff(new PinState(!pinned), new PinState(pinned))));
        LOG.info("Comment {} on case id {} pinned: {}", commentId, caseId, pinned);
    }

    /**
     * The audit snapshot of a comment's pin.
     *
     * @param pinned whether the comment is pinned
     */
    record PinState(boolean pinned) {}

    /**
     * The audit snapshot of a new comment: its text is possible PHI, so the trail holds only an HMAC and a length.
     *
     * @param body the comment's text
     */
    record PostedComment(@Redacted String body) {}
}
