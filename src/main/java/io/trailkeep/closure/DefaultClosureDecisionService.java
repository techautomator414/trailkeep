// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.closure;

import io.trailkeep.audit.AuditChanges;
import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.audit.Redacted;
import io.trailkeep.casefile.CaseScopeService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.CaseStatusService;
import io.trailkeep.casefile.CaseStatusService.MovedCase;
import io.trailkeep.notification.NotificationService;
import io.trailkeep.security.CaseAction;
import io.trailkeep.security.CasePermissionService;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.user.UserDirectoryService;
import jakarta.persistence.EntityManager;
import java.time.InstantSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides and reassigns; see {@link ClosureDecisionService}. Each operation first takes the case's row lock
 * through {@link CaseStatusService#move}, which re-reads the case and re-evaluates the matrix on what the lock holds.
 * Under that lock the request is read and re-checked (still pending; the actor is its approver; the approver is not
 * the owner), and written by one conditional update that matches only the pending row at the version read, so a
 * decision that lost a race changes nothing and is refused (409).
 */
@Service
@Transactional
@CaseAccessPath
class DefaultClosureDecisionService implements ClosureDecisionService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultClosureDecisionService.class);
    private static final String DECIDE = "UPDATE ClosureRequest r SET r.decision = :decision, r.decidedBy = :by,"
            + " r.decidedAt = :now, r.reason = :reason, r.version = r.version + 1"
            + " WHERE r.id = :id AND r.decision = :pending AND r.version = :version";
    private static final String REASSIGN = "UPDATE ClosureRequest r SET r.approverId = :approver,"
            + " r.version = r.version + 1 WHERE r.id = :id AND r.decision = :pending AND r.version = :version";
    private static final Map<ClosureDecision, String> VERBS = Map.of(
            ClosureDecision.APPROVED, "approved",
            ClosureDecision.REJECTED, "rejected",
            ClosureDecision.CHANGES_REQUESTED, "sent back for changes");

    private final CaseStatusService statuses;
    private final ClosureRequestRepository requests;
    private final CaseScopeService scopes;
    private final CasePermissionService permissions;
    private final UserDirectoryService directory;
    private final NotificationService notifications;
    private final AuditRecorder audit;
    private final EntityManager entityManager;
    private final InstantSource clock;

    DefaultClosureDecisionService(
            CaseStatusService statuses,
            ClosureRequestRepository requests,
            CaseScopeService scopes,
            CasePermissionService permissions,
            UserDirectoryService directory,
            NotificationService notifications,
            AuditRecorder audit,
            EntityManager entityManager,
            InstantSource clock) {
        this.statuses = statuses;
        this.requests = requests;
        this.scopes = scopes;
        this.permissions = permissions;
        this.directory = directory;
        this.notifications = notifications;
        this.audit = audit;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Override
    public void decide(
            Actor actor, String caseNumber, long requestId, ClosureDecision decision, @Nullable String reason) {
        if (decision == ClosureDecision.PENDING) {
            throw new IllegalArgumentException("a decision is not PENDING");
        }
        CaseStatus to = decision == ClosureDecision.APPROVED ? CaseStatus.CLOSED : CaseStatus.OPEN;
        MovedCase moved = statuses.move(actor, caseNumber, CaseAction.DECIDE_CLOSURE, CaseStatus.PENDING_CLOSURE, to);
        ClosureRequest request = pending(actor, moved, requestId);
        long actorId = actor.permissions().userId();
        if (request.getApproverId() != actorId || Objects.equals(moved.facts().ownerId(), actorId)) {
            throw new ClosureException(Kind.FORBIDDEN, "only the request's approver decides");
        }
        String text = reason(decision, reason);
        int changed = entityManager
                .createQuery(DECIDE)
                .setParameter("decision", decision)
                .setParameter("by", actorId)
                .setParameter("now", clock.instant())
                .setParameter("reason", text)
                .setParameter("id", requestId)
                .setParameter("pending", ClosureDecision.PENDING)
                .setParameter("version", request.getVersion())
                .executeUpdate();
        if (changed == 0) {
            throw new ClosureException(Kind.CONFLICT, "the request was decided meanwhile");
        }
        record(
                actor,
                moved,
                requestId,
                "CLOSURE_" + decision.name(),
                audit.diff(new Decided(ClosureDecision.PENDING, null), new Decided(decision, text)));
        // BUILD_PLAN §4.7: the owner and the requester, once each, never the approver who decided.
        Map<String, String> model =
                Map.of("decider", actor.displayName(), "verb", Objects.requireNonNull(VERBS.get(decision)));
        Stream.of(request.getRequestedBy(), moved.facts().ownerId())
                .filter(Objects::nonNull)
                .distinct()
                .forEach(person -> tell(person, actorId, "closure-decided", moved, model));
    }

    @Override
    public void reassignApprover(Actor actor, String caseNumber, long requestId, long approverId) {
        MovedCase moved = statuses.move(
                actor,
                caseNumber,
                CaseAction.REASSIGN_APPROVER,
                CaseStatus.PENDING_CLOSURE,
                CaseStatus.PENDING_CLOSURE);
        ClosureRequest request = pending(actor, moved, requestId);
        if (approverId == request.getApproverId()
                || !permissions
                        .approverCandidates(moved.facts(), request.getRequestedBy())
                        .contains(approverId)) {
            throw new ClosureException(Kind.INVALID, "not an approver for this case");
        }
        int changed = entityManager
                .createQuery(REASSIGN)
                .setParameter("approver", approverId)
                .setParameter("id", requestId)
                .setParameter("pending", ClosureDecision.PENDING)
                .setParameter("version", request.getVersion())
                .executeUpdate();
        if (changed == 0) {
            throw new ClosureException(Kind.CONFLICT, "the request was decided meanwhile");
        }
        record(
                actor,
                moved,
                requestId,
                "APPROVER_REASSIGNED",
                audit.diff(new Reassigned(name(request.getApproverId())), new Reassigned(name(approverId))));
        tell(
                approverId,
                actor.permissions().userId(),
                "closure-requested",
                moved,
                Map.of("requester", name(request.getRequestedBy()), "outcome", request.getOutcome()));
    }

    /** The reason as stored (CRLF as one line break, stripped, {@code null} if empty); 422 if missing or too long. */
    private static @Nullable String reason(ClosureDecision decision, @Nullable String reason) {
        String text = reason == null ? "" : reason.replace("\r\n", "\n").strip();
        if (text.length() > MAX_REASON || (text.isEmpty() && decision != ClosureDecision.APPROVED)) {
            throw new ClosureException(Kind.INVALID, "a reason of 1 to 1000 characters is required");
        }
        return text.isEmpty() ? null : text;
    }

    /** The case's request, read under the case lock: 404 if it is not on this case, 409 if no longer pending. */
    private ClosureRequest pending(Actor actor, MovedCase moved, long requestId) {
        ClosureRequest request =
                requests
                        .findForCase(
                                scopes.forUser(actor.permissions()), moved.ref().number())
                        .stream()
                        .filter(r -> Objects.equals(r.getId(), requestId))
                        .findFirst()
                        .orElseThrow(
                                () -> new ClosureException(Kind.NOT_FOUND, "no such closure request on this case"));
        if (request.getDecision() != ClosureDecision.PENDING) {
            throw new ClosureException(Kind.CONFLICT, "the request is already decided");
        }
        return request;
    }

    private void record(Actor actor, MovedCase moved, long requestId, String action, AuditChanges changes) {
        audit.record(new AuditEntry(
                actor.permissions().userId(),
                actor.displayName(),
                AuditOrigin.UI,
                moved.ref().id(),
                "CLOSURE_REQUEST",
                requestId,
                action,
                changes));
        // The action is in the audit trail; the log carries ids only.
        LOG.info(
                "Closure request {} on case id {} decided or reassigned",
                requestId,
                moved.ref().id());
    }

    /** Mail and alert with number, title and a link, plus the template's own values; never a reason or summary. */
    private void tell(long userId, long actorId, String template, MovedCase moved, Map<String, String> values) {
        if (userId == actorId) {
            return;
        }
        Map<String, String> model = new HashMap<>(values);
        model.put("caseNumber", moved.ref().number().toString());
        model.put("title", moved.ref().title());
        notifications.notifyUser(
                userId,
                template,
                model,
                moved.ref().id(),
                "/cases/" + moved.ref().number());
    }

    private String name(long userId) {
        return directory.displayName(userId).orElse("");
    }

    /**
     * The audit snapshot of a decision: the reason may name people or facts of the case, so it is redacted.
     *
     * @param decision the decision
     * @param reason why, or {@code null}
     */
    record Decided(
            ClosureDecision decision, @Redacted @Nullable String reason) {}

    /**
     * The audit snapshot of the approver.
     *
     * @param approver who decides, by name
     */
    record Reassigned(String approver) {}
}
