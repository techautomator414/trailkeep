// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.updaterequest;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.audit.Redacted;
import io.trailkeep.casefile.CaseScopeService;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.CaseService.CaseRef;
import io.trailkeep.casefile.CommentPosted;
import io.trailkeep.notification.NotificationService;
import io.trailkeep.security.CaseAction;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Paged;
import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.UserDirectoryService;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.hibernate.exception.ConstraintViolationException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asks, answers and lists update requests. A request, its audit event, the recipient's alert and their mail are
 * written in one transaction. Answering is one conditional update that only an open row matches, so a comment and a
 * "Mark answered" racing each other answer a request once and audit it once. The second open request to one person on
 * one case is refused before the insert (409) and, under a race, by the {@code OPEN_KEY} unique constraint (409 too).
 */
@Service
@Transactional
@CaseAccessPath
class DefaultUpdateRequestService implements UpdateRequestService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultUpdateRequestService.class);
    private static final String ANSWER = "UPDATE UpdateRequest r SET r.answeredAt = :now, r.answeredBy = :by,"
            + " r.answeredByCommentId = :comment, r.openKey = NULL WHERE r.id = :id AND r.answeredAt IS NULL";
    private static final String DUE = "SELECT r.id, r.caseId, r.requestedFrom, r.requestedBy FROM UpdateRequest r"
            + " WHERE r.answeredAt IS NULL AND r.id > :after AND COALESCE(r.lastRemindedAt, r.requestedAt) <= :cutoff"
            + " ORDER BY r.id";
    private static final String REMINDED =
            "UPDATE UpdateRequest r SET r.lastRemindedAt = :now WHERE r.id = :id AND r.answeredAt IS NULL";

    private final UpdateRequestRepository requests;
    private final CaseService cases;
    private final CaseScopeService scopes;
    private final UserDirectoryService directory;
    private final NotificationService notifications;
    private final AuditRecorder audit;
    private final EntityManager entityManager;
    private final InstantSource clock;
    private final ZoneId zone;

    DefaultUpdateRequestService(
            UpdateRequestRepository requests,
            CaseService cases,
            CaseScopeService scopes,
            UserDirectoryService directory,
            NotificationService notifications,
            AuditRecorder audit,
            EntityManager entityManager,
            InstantSource clock,
            AppProperties app) {
        this.requests = requests;
        this.cases = cases;
        this.scopes = scopes;
        this.directory = directory;
        this.notifications = notifications;
        this.audit = audit;
        this.entityManager = entityManager;
        this.clock = clock;
        this.zone = app.timezone();
    }

    @Override
    public long request(Actor actor, String caseNumber, long recipientId, String message) {
        CaseRef ref = cases.actionable(actor, caseNumber, CaseAction.REQUEST_UPDATE);
        // A browser submits a textarea's line breaks as CRLF; counted as one, as the form's maxlength counts them.
        String text = message.replace("\r\n", "\n");
        if (text.isBlank() || text.length() > UpdateRequest.MAX_MESSAGE) {
            throw new UpdateRequestException(Kind.INVALID, "a request message is 1 to 2000 characters");
        }
        if (recipientId == actor.permissions().userId() || !readsCase(recipientId, ref.number())) {
            throw new UpdateRequestException(Kind.INVALID, "the recipient cannot read this case");
        }
        if (open(scopes.forUser(actor.permissions()), ref.number())
                .anyMatch(r -> r.getRequestedFrom() == recipientId)) {
            throw new UpdateRequestException(Kind.CONFLICT, "an update is already requested from this person");
        }
        long actorId = actor.permissions().userId();
        UpdateRequest request = new UpdateRequest(ref.id(), actorId, recipientId, text, clock.instant());
        try {
            entityManager.persist(request);
            entityManager.flush();
        } catch (ConstraintViolationException raced) {
            throw new UpdateRequestException(Kind.CONFLICT, "an update is already requested from this person");
        }
        long id = Objects.requireNonNull(request.getId());
        String recipientName = directory.displayName(recipientId).orElse("");
        audit.record(new AuditEntry(
                actorId,
                actor.displayName(),
                AuditOrigin.UI,
                ref.id(),
                "UPDATE_REQUEST",
                id,
                "UPDATE_REQUESTED",
                audit.diff(null, new Requested(recipientName, text))));
        // Number, title, who asks and a link: never the message (BUILD_PLAN §9).
        notifications.notifyUser(
                recipientId,
                "update-requested",
                Map.of("caseNumber", ref.number().toString(), "title", ref.title(), "requester", actor.displayName()),
                ref.id(),
                "/cases/" + ref.number() + "#comments");
        LOG.info("Update request {} on case id {} sent to user {}", id, ref.id(), recipientId);
        return id;
    }

    @Override
    public void markAnswered(Actor actor, String caseNumber, long requestId) {
        CaseNumber number = cases.actionable(actor, caseNumber, CaseAction.VIEW).number();
        UpdateRequest request = requests.findForCase(scopes.forUser(actor.permissions()), number).stream()
                .filter(r -> Objects.equals(r.getId(), requestId))
                .findFirst()
                .orElseThrow(() -> new UpdateRequestException(Kind.NOT_FOUND, "no such request on this case"));
        boolean own = request.getRequestedBy() == actor.permissions().userId();
        cases.actionable(actor, caseNumber, own ? CaseAction.REQUEST_UPDATE : CaseAction.ANSWER_UPDATE);
        if (!answer(request, actor.permissions().userId(), actor.displayName(), null)) {
            throw new UpdateRequestException(Kind.CONFLICT, "this request is already answered");
        }
    }

    /**
     * Answers the author's open requests on the case they just commented on, in the posting transaction ({@link
     * UpdateRequestCommentListener}). The system scope is right here: the comment was authorized when it was posted.
     *
     * @param posted the comment
     */
    public void answerByComment(CommentPosted posted) {
        open(CaseScope.system(), posted.caseNumber())
                .filter(r -> r.getRequestedFrom() == posted.authorId())
                .forEach(r -> answer(r, posted.authorId(), posted.authorName(), posted.commentId()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CaseRequest> openForCase(Actor actor, String caseNumber) {
        CaseNumber number = cases.actionable(actor, caseNumber, CaseAction.VIEW).number();
        List<UpdateRequest> found =
                open(scopes.forUser(actor.permissions()), number).toList();
        boolean asRequester = !found.isEmpty() && cases.allows(actor, caseNumber, CaseAction.REQUEST_UPDATE);
        boolean asManager = !found.isEmpty() && cases.allows(actor, caseNumber, CaseAction.ANSWER_UPDATE);
        Map<Long, String> names = directory.displayNames(found.stream()
                .flatMap(r -> Stream.of(r.getRequestedBy(), r.getRequestedFrom()))
                .distinct()
                .toList());
        return found.stream()
                .map(r -> new CaseRequest(
                        Objects.requireNonNull(r.getId()),
                        r.getRequestedBy(),
                        names.getOrDefault(r.getRequestedBy(), ""),
                        names.getOrDefault(r.getRequestedFrom(), ""),
                        LocalDateTime.ofInstant(r.getRequestedAt(), zone).truncatedTo(ChronoUnit.MINUTES),
                        r.getMessage(),
                        r.getRequestedBy() == actor.permissions().userId() ? asRequester : asManager))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Recipient> recipients(Actor actor, String caseNumber) {
        CaseRef ref = cases.actionable(actor, caseNumber, CaseAction.REQUEST_UPDATE);
        Set<Long> people = new TreeSet<>(directory.activeMembersOf(ref.teamId()));
        people.addAll(directory.activeComplianceAdmins());
        people.remove(actor.permissions().userId());
        List<Long> readers =
                people.stream().filter(id -> readsCase(id, ref.number())).toList();
        Map<Long, String> names = directory.displayNames(readers);
        return readers.stream()
                .map(id -> new Recipient(id, names.getOrDefault(id, "")))
                .sorted(Comparator.comparing(Recipient::name))
                .toList();
    }

    /** Whether a person, as the database holds their roles now, is enabled and reads the case. */
    private boolean readsCase(long userId, CaseNumber number) {
        UserPermissions person = directory.permissionsOf(userId);
        return !person.disabled() && scopes.forUser(person).canRead(number);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DueReminder> dueForReminder(Instant cutoff, long afterId, int limit) {
        return entityManager
                .createQuery(DUE, Tuple.class)
                .setParameter("after", afterId)
                .setParameter("cutoff", cutoff)
                .setMaxResults(limit)
                .getResultStream()
                .map(row -> new DueReminder(
                        row.get(0, Long.class), row.get(1, Long.class), row.get(2, Long.class), row.get(3, Long.class)))
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reminded(long requestId, Instant now) {
        return entityManager
                        .createQuery(REMINDED)
                        .setParameter("now", now)
                        .setParameter("id", requestId)
                        .executeUpdate()
                == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public Paged<OpenRequest> openFor(Actor actor, int page) {
        CaseScope scope = scopes.forUser(actor.permissions());
        long me = actor.permissions().userId();
        return Paged.of(
                page,
                PAGE_SIZE,
                () -> requests.countOpenFrom(scope, me),
                offset -> rows(actor, requests.findOpenFrom(scope, me, offset, PAGE_SIZE)));
    }

    private List<OpenRequest> rows(Actor actor, List<UpdateRequest> open) {
        Collection<Long> caseIds =
                open.stream().map(UpdateRequest::getCaseId).distinct().toList();
        Map<Long, CaseRef> refs = cases.refs(actor, caseIds);
        Map<Long, String> names = directory.displayNames(
                open.stream().map(UpdateRequest::getRequestedBy).distinct().toList());
        return open.stream()
                .filter(r -> refs.containsKey(r.getCaseId()))
                .map(r -> {
                    CaseRef ref = Objects.requireNonNull(refs.get(r.getCaseId()));
                    return new OpenRequest(
                            Objects.requireNonNull(r.getId()),
                            ref.number(),
                            ref.title(),
                            names.getOrDefault(r.getRequestedBy(), ""),
                            r.getRequestedAt());
                })
                .toList();
    }

    /** Answers an open request once and audits it; {@code false} if it was already answered. */
    private boolean answer(UpdateRequest request, long by, String byName, @Nullable Long commentId) {
        long id = Objects.requireNonNull(request.getId());
        int changed = entityManager
                .createQuery(ANSWER)
                .setParameter("now", clock.instant())
                .setParameter("by", by)
                .setParameter("comment", commentId)
                .setParameter("id", id)
                .executeUpdate();
        if (changed == 0) {
            return false;
        }
        audit.record(new AuditEntry(
                by,
                byName,
                AuditOrigin.UI,
                request.getCaseId(),
                "UPDATE_REQUEST",
                id,
                "UPDATE_ANSWERED",
                audit.diff(new Answered(false, null), new Answered(true, commentId))));
        LOG.info("Update request {} on case id {} answered", id, request.getCaseId());
        return true;
    }

    private Stream<UpdateRequest> open(CaseScope scope, CaseNumber number) {
        return requests.findForCase(scope, number).stream().filter(UpdateRequest::isOpen);
    }

    /**
     * The audit snapshot of a new request: the message is possible PHI, so the trail holds only an HMAC and a length.
     *
     * @param recipient who was asked, by name
     * @param message what was asked
     */
    record Requested(String recipient, @Redacted String message) {}

    /**
     * The audit snapshot of a request's answer.
     *
     * @param answered whether it is answered
     * @param comment the comment that answered it, or {@code null}
     */
    record Answered(boolean answered, @Nullable Long comment) {}
}
