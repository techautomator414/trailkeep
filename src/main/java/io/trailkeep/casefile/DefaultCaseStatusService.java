// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.CaseService.CaseRef;
import io.trailkeep.security.CaseAction;
import io.trailkeep.security.CaseFacts;
import io.trailkeep.security.CasePermissionService;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.shared.config.AppProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closure's view of a case and its status moves; see {@link CaseStatusService}. A move also keeps the SLA side table
 * (ADR-0007): closing finalises the state, reopening starts the next cycle from the reopen instant.
 */
@Service
@Transactional
@CaseAccessPath
class DefaultCaseStatusService implements CaseStatusService {

    private final CaseLoader loader;
    private final CaseTypeOutcomeRepository outcomes;
    private final CaseTypeRepository caseTypes;
    private final SlaPolicyRepository policies;
    private final CasePermissionService permissions;
    private final EntityManager entityManager;
    private final InstantSource clock;
    private final ZoneId zone;

    DefaultCaseStatusService(
            CaseLoader loader,
            CaseTypeOutcomeRepository outcomes,
            CaseTypeRepository caseTypes,
            SlaPolicyRepository policies,
            CasePermissionService permissions,
            EntityManager entityManager,
            InstantSource clock,
            AppProperties app) {
        this.loader = loader;
        this.outcomes = outcomes;
        this.caseTypes = caseTypes;
        this.policies = policies;
        this.permissions = permissions;
        this.entityManager = entityManager;
        this.clock = clock;
        this.zone = app.timezone();
    }

    @Override
    @Transactional(readOnly = true)
    public ClosableCase closable(Actor actor, String caseNumber, CaseAction action) {
        CaseRecord record = loader.load(actor.permissions(), caseNumber, action);
        return new ClosableCase(
                ref(record),
                CaseLoader.facts(record),
                outcomes.findByIdCaseTypeIdOrderBySortOrderAsc(record.getCaseTypeId()).stream()
                        .map(CaseTypeOutcome::outcome)
                        .toList());
    }

    @Override
    public MovedCase move(Actor actor, String caseNumber, CaseAction action, CaseStatus from, CaseStatus to) {
        CaseRecord record = loader.load(actor.permissions(), caseNumber, action);
        // Re-read under the row lock, then decide again on what the lock holds: another request or decision may have
        // moved the case, or changed what the matrix decides on, since it was loaded.
        entityManager.refresh(record, LockModeType.PESSIMISTIC_WRITE);
        if (record.getStatus() != from) {
            throw new CaseException(Kind.CONFLICT, "the case is no longer " + from);
        }
        CaseFacts facts = CaseLoader.facts(record);
        if (!permissions.isAllowed(actor.permissions(), facts, action)) {
            throw new CaseException(Kind.FORBIDDEN, "action not allowed on this case");
        }
        Instant now = clock.instant();
        record.moveTo(to, actor.permissions().userId(), now);
        CaseSla sla = entityManager.find(CaseSla.class, Objects.requireNonNull(record.getId()));
        if (sla == null) {
            // Every case has its row from creation (or import, as NOT_APPLICABLE); a status move never invents one.
            throw new IllegalStateException("case " + record.getId() + " has no SLA row");
        }
        if (to == CaseStatus.CLOSED) {
            sla.finish(now);
        } else if (from == CaseStatus.CLOSED && to == CaseStatus.OPEN) {
            sla.restart(SlaClock.start(restartPolicy(record), LocalDate.ofInstant(now, zone), now, zone));
        }
        return new MovedCase(ref(record), facts, sla.getCycle());
    }

    /** The case type's policy, anchored on the reopen instant (BUILD_PLAN §6 "One anchor rule"). */
    private SlaPolicyCommand restartPolicy(CaseRecord record) {
        SlaPolicyCommand policy = caseTypes
                .findById(record.getCaseTypeId())
                .flatMap(type -> policies.findById(type.getSlaPolicyId()))
                .orElseThrow(() -> new IllegalStateException("a case type has a policy"))
                .toCommand();
        return new SlaPolicyCommand(
                policy.name(),
                SlaAnchor.OPENED_AT,
                policy.targetDays(),
                policy.atRiskPct(),
                policy.overdueReminderDays(),
                policy.escalateTo());
    }

    private static CaseRef ref(CaseRecord record) {
        return new CaseRef(
                Objects.requireNonNull(record.getId()), record.getCaseNumber(), record.getTitle(), record.getTeamId());
    }
}
