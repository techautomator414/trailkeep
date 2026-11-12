// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.closure;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.audit.Redacted;
import io.trailkeep.casefile.CaseCommentService;
import io.trailkeep.casefile.CaseScopeService;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.CaseService.CaseRef;
import io.trailkeep.casefile.CaseStatusService;
import io.trailkeep.casefile.CaseStatusService.ClosableCase;
import io.trailkeep.notification.NotificationService;
import io.trailkeep.security.CaseAction;
import io.trailkeep.security.CasePermissionService;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Paged;
import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.UserDirectoryService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Requests closure. The case's move to {@code PENDING_CLOSURE} (under its row lock), the request, its audit event and
 * the approver's alert and mail are written in one transaction; a second request racing the first finds the case no
 * longer open once locked and is refused (409), so a case has at most one pending request.
 */
@Service
@Transactional
@CaseAccessPath
class DefaultClosureService implements ClosureService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultClosureService.class);
    private static final String PENDING_SINCE =
            "SELECT r.id, r.caseId, r.approverId, r.requestedBy, r.outcome FROM ClosureRequest r"
                    + " WHERE r.decision = :pending AND r.requestedAt <= :cutoff AND r.id > :after ORDER BY r.id";

    private final CaseStatusService statuses;
    private final CasePermissionService permissions;
    private final UserDirectoryService directory;
    private final NotificationService notifications;
    private final AuditRecorder audit;
    private final EntityManager entityManager;
    private final ClosureRequestRepository requests;
    private final CaseReopenRepository reopens;
    private final CaseScopeService scopes;
    private final CaseService cases;
    private final CaseCommentService comments;
    private final InstantSource clock;
    private final ZoneId zone;

    DefaultClosureService(
            CaseStatusService statuses,
            CasePermissionService permissions,
            UserDirectoryService directory,
            NotificationService notifications,
            AuditRecorder audit,
            EntityManager entityManager,
            ClosureRequestRepository requests,
            CaseReopenRepository reopens,
            CaseScopeService scopes,
            CaseService cases,
            CaseCommentService comments,
            InstantSource clock,
            AppProperties app) {
        this.statuses = statuses;
        this.permissions = permissions;
        this.directory = directory;
        this.notifications = notifications;
        this.audit = audit;
        this.entityManager = entityManager;
        this.requests = requests;
        this.reopens = reopens;
        this.scopes = scopes;
        this.cases = cases;
        this.comments = comments;
        this.clock = clock;
        this.zone = app.timezone();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AwaitingDecision> pendingSince(Instant cutoff, long afterId, int limit) {
        return entityManager
                .createQuery(PENDING_SINCE, Tuple.class)
                .setParameter("pending", ClosureDecision.PENDING)
                .setParameter("cutoff", cutoff)
                .setParameter("after", afterId)
                .setMaxResults(limit)
                .getResultStream()
                .map(row -> new AwaitingDecision(
                        row.get(0, Long.class),
                        row.get(1, Long.class),
                        row.get(2, Long.class),
                        row.get(3, Long.class),
                        row.get(4, String.class)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Reopened> lastReopen(Actor actor, String caseNumber) {
        CaseNumber number = parsed(caseNumber);
        List<CaseReopen> all =
                number == null ? List.of() : reopens.findForCase(scopes.forUser(actor.permissions()), number);
        return all.stream()
                .findFirst()
                .map(r -> new Reopened(
                        directory.displayName(r.getReopenedBy()).orElse(""),
                        minute(r.getReopenedAt()),
                        r.getReason(),
                        r.getSlaCycle()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DecidedClosure> lastDecision(Actor actor, String caseNumber) {
        Optional<ClosureRequest> latest = forCase(actor, caseNumber).stream().findFirst();
        if (latest.isEmpty() || latest.get().getDecision() == ClosureDecision.PENDING) {
            return Optional.empty();
        }
        ClosureRequest r = latest.get();
        Instant decidedAt = Objects.requireNonNull(r.getDecidedAt());
        boolean sentBack = r.getDecision() != ClosureDecision.APPROVED
                && comments.lastCommentAt(actor, caseNumber)
                        .filter(at -> at.isAfter(decidedAt))
                        .isEmpty();
        return Optional.of(new DecidedClosure(
                Objects.requireNonNull(r.getId()),
                r.getDecision(),
                directory.displayName(Objects.requireNonNull(r.getDecidedBy())).orElse(""),
                minute(decidedAt),
                r.getReason(),
                r.getOutcome(),
                r.getResolutionSummary(),
                r.getFollowUpActions(),
                sentBack,
                r.isImported()));
    }

    @Override
    @Transactional(readOnly = true)
    public PendingClosure decidable(Actor actor, String caseNumber, long requestId) {
        statuses.closable(actor, caseNumber, CaseAction.DECIDE_CLOSURE);
        ClosureRequest request = pendingRequest(actor, caseNumber, requestId);
        if (request.getApproverId() != actor.permissions().userId()) {
            throw new ClosureException(Kind.FORBIDDEN, "only the request's approver decides");
        }
        return view(request);
    }

    @Override
    @Transactional(readOnly = true)
    public Paged<AwaitingApproval> awaitingMyApproval(Actor actor, int page) {
        CaseScope scope = scopes.forUser(actor.permissions());
        long me = actor.permissions().userId();
        return Paged.of(
                page,
                PAGE_SIZE,
                () -> requests.countPendingFor(scope, me),
                offset -> awaiting(actor, requests.findPendingFor(scope, me, offset, PAGE_SIZE)));
    }

    private List<AwaitingApproval> awaiting(Actor actor, List<ClosureRequest> pending) {
        Map<Long, CaseRef> refs = cases.refs(
                actor, pending.stream().map(ClosureRequest::getCaseId).toList());
        Map<Long, String> names = directory.displayNames(
                pending.stream().map(ClosureRequest::getRequestedBy).distinct().toList());
        return pending.stream()
                .filter(r -> refs.containsKey(r.getCaseId()))
                .map(r -> {
                    CaseRef ref = Objects.requireNonNull(refs.get(r.getCaseId()));
                    return new AwaitingApproval(
                            Objects.requireNonNull(r.getId()),
                            ref.number(),
                            ref.title(),
                            names.getOrDefault(r.getRequestedBy(), ""),
                            r.getOutcome());
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Approver> reassignChoices(Actor actor, String caseNumber, long requestId) {
        ClosableCase closable = statuses.closable(actor, caseNumber, CaseAction.REASSIGN_APPROVER);
        ClosureRequest request = pendingRequest(actor, caseNumber, requestId);
        return named(permissions.approverCandidates(closable.facts(), request.getRequestedBy()).stream()
                .filter(id -> id != request.getApproverId())
                .toList());
    }

    private ClosureRequest pendingRequest(Actor actor, String caseNumber, long requestId) {
        return forCase(actor, caseNumber).stream()
                .filter(r -> r.getDecision() == ClosureDecision.PENDING && Objects.equals(r.getId(), requestId))
                .findFirst()
                .orElseThrow(() -> new ClosureException(Kind.NOT_FOUND, "no such pending request on this case"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Approver> approvers(Actor actor, String caseNumber) {
        return approversOf(actor, statuses.closable(actor, caseNumber));
    }

    @Override
    @Transactional(readOnly = true)
    public ClosureChoices choices(Actor actor, String caseNumber) {
        ClosableCase closable = statuses.closable(actor, caseNumber);
        return new ClosureChoices(closable.outcomes(), approversOf(actor, closable));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PendingClosure> pending(Actor actor, String caseNumber) {
        return forCase(actor, caseNumber).stream()
                .filter(r -> r.getDecision() == ClosureDecision.PENDING)
                .findFirst()
                .map(this::view);
    }

    private PendingClosure view(ClosureRequest r) {
        return new PendingClosure(
                Objects.requireNonNull(r.getId()),
                r.getOutcome(),
                directory.displayName(r.getRequestedBy()).orElse(""),
                r.getApproverId(),
                directory.displayName(r.getApproverId()).orElse(""),
                minute(r.getRequestedAt()),
                r.getResolutionSummary(),
                r.getFollowUpActions());
    }

    /** The case's requests, newest first; none for a malformed, absent or unreadable case. */
    private List<ClosureRequest> forCase(Actor actor, String caseNumber) {
        CaseNumber number = parsed(caseNumber);
        return number == null ? List.of() : requests.findForCase(scopes.forUser(actor.permissions()), number);
    }

    /** The case number, or {@code null} for a malformed one (which reads as an absent case). */
    private static @Nullable CaseNumber parsed(String caseNumber) {
        try {
            return CaseNumber.parse(caseNumber);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private LocalDateTime minute(Instant at) {
        return LocalDateTime.ofInstant(at, zone).truncatedTo(ChronoUnit.MINUTES);
    }

    private List<Approver> approversOf(Actor actor, ClosableCase closable) {
        return named(permissions.approverCandidates(
                closable.facts(), actor.permissions().userId()));
    }

    private List<Approver> named(List<Long> ids) {
        Map<Long, String> names = directory.displayNames(ids);
        return ids.stream()
                .map(id -> new Approver(id, names.getOrDefault(id, "")))
                .sorted(Comparator.comparing(Approver::name))
                .toList();
    }

    @Override
    public long requestClosure(
            Actor actor,
            String caseNumber,
            String outcome,
            String summary,
            @Nullable String followUps,
            long approverId) {
        ClosableCase closable = statuses.closable(actor, caseNumber);
        String text = text(summary);
        final String follow = followUps == null || followUps.isBlank() ? null : text(followUps);
        if (text.isBlank() || !closable.outcomes().contains(outcome)) {
            throw new ClosureException(Kind.INVALID, "a closure needs one of the case type's outcomes and a summary");
        }
        long requester = actor.permissions().userId();
        if (!permissions.approverCandidates(closable.facts(), requester).contains(approverId)) {
            throw new ClosureException(Kind.INVALID, "not an approver for this case");
        }
        statuses.move(actor, caseNumber, CaseAction.REQUEST_CLOSURE, CaseStatus.OPEN, CaseStatus.PENDING_CLOSURE);
        long caseId = closable.ref().id();
        ClosureRequest request =
                new ClosureRequest(caseId, requester, approverId, outcome, text, follow, clock.instant());
        entityManager.persist(request);
        entityManager.flush();
        long id = Objects.requireNonNull(request.getId());
        audit.record(new AuditEntry(
                requester,
                actor.displayName(),
                AuditOrigin.UI,
                caseId,
                "CLOSURE_REQUEST",
                id,
                "CLOSURE_REQUESTED",
                audit.diff(
                        null,
                        new Requested(
                                outcome,
                                text,
                                follow,
                                directory.displayName(approverId).orElse("")))));
        // Number, title, requester, outcome and a link: never the summary or the follow-ups (BUILD_PLAN §9).
        notifications.notifyUser(
                approverId,
                "closure-requested",
                Map.of(
                        "caseNumber", closable.ref().number().toString(),
                        "title", closable.ref().title(),
                        "requester", actor.displayName(),
                        "outcome", outcome),
                caseId,
                "/cases/" + closable.ref().number());
        LOG.info("Closure request {} on case id {} sent to user {}", id, caseId, approverId);
        return id;
    }

    /** CRLF as one line break, as the form's maxlength counts it; 422 beyond the cap. */
    private static String text(String value) {
        String text = value.replace("\r\n", "\n");
        if (text.length() > ClosureRequest.MAX_TEXT) {
            throw new ClosureException(Kind.INVALID, "closure texts are at most 5000 characters");
        }
        return text;
    }

    /**
     * The audit snapshot of a closure request: summary and follow-ups are possible PHI, so the trail holds only an
     * HMAC and a length of each.
     *
     * @param outcome the outcome
     * @param summary the resolution summary
     * @param followUps the follow-up actions, or {@code null}
     * @param approver who decides, by name
     */
    record Requested(
            String outcome,
            @Redacted String summary,
            @Redacted @Nullable String followUps,
            String approver) {}
}
