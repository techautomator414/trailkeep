// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.closure;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.audit.Redacted;
import io.trailkeep.casefile.CaseScopeService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.CaseStatusService;
import io.trailkeep.casefile.CaseStatusService.ClosableCase;
import io.trailkeep.notification.NotificationService;
import io.trailkeep.security.CaseAction;
import io.trailkeep.security.CasePermissionService;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.UserDirectoryService;
import jakarta.persistence.EntityManager;
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

    private final CaseStatusService statuses;
    private final CasePermissionService permissions;
    private final UserDirectoryService directory;
    private final NotificationService notifications;
    private final AuditRecorder audit;
    private final EntityManager entityManager;
    private final ClosureRequestRepository requests;
    private final CaseScopeService scopes;
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
            CaseScopeService scopes,
            InstantSource clock,
            AppProperties app) {
        this.statuses = statuses;
        this.permissions = permissions;
        this.directory = directory;
        this.notifications = notifications;
        this.audit = audit;
        this.entityManager = entityManager;
        this.requests = requests;
        this.scopes = scopes;
        this.clock = clock;
        this.zone = app.timezone();
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
        CaseNumber number;
        try {
            number = CaseNumber.parse(caseNumber);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        return requests.findForCase(scopes.forUser(actor.permissions()), number).stream()
                .filter(r -> r.getDecision() == ClosureDecision.PENDING)
                .findFirst()
                .map(r -> new PendingClosure(
                        Objects.requireNonNull(r.getId()),
                        r.getOutcome(),
                        directory.displayName(r.getRequestedBy()).orElse(""),
                        r.getApproverId(),
                        directory.displayName(r.getApproverId()).orElse(""),
                        LocalDateTime.ofInstant(r.getRequestedAt(), zone).truncatedTo(ChronoUnit.MINUTES),
                        r.getResolutionSummary(),
                        r.getFollowUpActions()));
    }

    private List<Approver> approversOf(Actor actor, ClosableCase closable) {
        List<Long> ids = permissions.approverCandidates(
                closable.facts(), actor.permissions().userId());
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
