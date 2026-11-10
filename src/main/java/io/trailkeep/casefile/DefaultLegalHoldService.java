// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.audit.Redacted;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.security.CaseAction;
import io.trailkeep.security.CasePermissionService;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.UserDirectoryService;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Places and lifts legal holds; see {@link LegalHoldService}. The change re-reads the case under its row lock, so two
 * Compliance Admins acting at once yield one change and one {@code CONFLICT}, and the archive job's conditional update
 * either runs before the hold (and the case is archived, then held) or sees it (and skips the case).
 */
@Service
@Transactional
class DefaultLegalHoldService implements LegalHoldService {

    private static final String HISTORY =
            "SELECT e FROM LegalHoldEvent e WHERE e.caseId = :caseId ORDER BY e.changedAt DESC, e.id DESC";

    private final CaseLoader loader;
    private final CasePermissionService permissions;
    private final UserDirectoryService directory;
    private final AuditRecorder audit;
    private final EntityManager entityManager;
    private final InstantSource clock;
    private final Duration grace;
    private final ZoneId zone;

    DefaultLegalHoldService(
            CaseLoader loader,
            CasePermissionService permissions,
            UserDirectoryService directory,
            AuditRecorder audit,
            EntityManager entityManager,
            InstantSource clock,
            @Value("${app.retention.purge-grace:P30D}") Duration grace,
            AppProperties app) {
        this.loader = loader;
        this.permissions = permissions;
        this.directory = directory;
        this.audit = audit;
        this.entityManager = entityManager;
        this.clock = clock;
        this.grace = grace;
        this.zone = app.timezone();
    }

    /**
     * What the audit trail keeps of a hold being placed or lifted.
     *
     * @param legalHold whether the case is held
     * @param reason why it changed, redacted
     */
    record Change(boolean legalHold, @Redacted String reason) {}

    @Override
    @Transactional(readOnly = true)
    public Optional<LegalHold> current(Actor actor, String caseNumber) {
        UserPermissions user = actor.permissions();
        CaseRecord record = loader.load(user, caseNumber, CaseAction.VIEW);
        if (!record.isLegalHold()) {
            return Optional.empty();
        }
        long heldBy = Objects.requireNonNullElse(record.getLegalHoldBy(), 0L);
        boolean sees = permissions.isAllowed(user, CaseLoader.facts(record), CaseAction.LEGAL_HOLD);
        return Optional.of(new LegalHold(
                directory.displayName(heldBy).orElse(""),
                LocalDateTime.ofInstant(Objects.requireNonNullElse(record.getLegalHoldAt(), Instant.EPOCH), zone),
                sees ? Objects.requireNonNullElse(record.getLegalHoldReason(), "") : ""));
    }

    @Override
    @Transactional(readOnly = true)
    public List<HoldChange> history(Actor actor, String caseNumber) {
        UserPermissions user = actor.permissions();
        CaseRecord record = loader.load(user, caseNumber, CaseAction.VIEW);
        if (!permissions.isAllowed(user, CaseLoader.facts(record), CaseAction.LEGAL_HOLD)) {
            return List.of();
        }
        List<LegalHoldEvent> events = entityManager
                .createQuery(HISTORY, LegalHoldEvent.class)
                .setParameter("caseId", record.getId())
                .getResultList();
        Map<Long, String> names = directory.displayNames(
                events.stream().map(LegalHoldEvent::getChangedBy).distinct().toList());
        return events.stream()
                .map(event -> new HoldChange(
                        event.getAction() == LegalHoldEvent.Action.PLACED,
                        names.getOrDefault(event.getChangedBy(), ""),
                        LocalDateTime.ofInstant(event.getChangedAt(), zone),
                        event.getReason()))
                .toList();
    }

    @Override
    public void change(Actor actor, String caseNumber, boolean hold, String reason) {
        UserPermissions user = actor.permissions();
        CaseRecord record = loader.load(user, caseNumber, CaseAction.LEGAL_HOLD);
        String text = reason.replace("\r\n", "\n").strip();
        if (text.isEmpty() || text.length() > MAX_REASON) {
            throw new CaseException(Kind.INVALID, "a reason is 1 to " + MAX_REASON + " characters");
        }
        entityManager.refresh(record, LockModeType.PESSIMISTIC_WRITE);
        if (record.isLegalHold() == hold) {
            throw new CaseException(Kind.CONFLICT, hold ? "the case is already held" : "the case is not held");
        }
        Instant now = clock.instant();
        if (hold) {
            record.hold(user.userId(), now, text);
        } else {
            record.release(user.userId(), now, grace);
        }
        long caseId = Objects.requireNonNull(record.getId());
        entityManager.persist(new LegalHoldEvent(
                caseId,
                hold ? LegalHoldEvent.Action.PLACED : LegalHoldEvent.Action.LIFTED,
                user.userId(),
                now,
                text,
                record.getPurgeAfter()));
        audit.record(new AuditEntry(
                user.userId(),
                actor.displayName(),
                AuditOrigin.UI,
                caseId,
                "CASE",
                caseId,
                hold ? "LEGAL_HOLD_PLACED" : "LEGAL_HOLD_LIFTED",
                audit.diff(new Change(!hold, ""), new Change(hold, text))));
    }
}
