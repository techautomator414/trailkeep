// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.CaseService.CaseRef;
import io.trailkeep.security.CaseAction;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.access.CaseAccessPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.InstantSource;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Closure's view of a case and its status moves; see {@link CaseStatusService}. */
@Service
@Transactional
@CaseAccessPath
class DefaultCaseStatusService implements CaseStatusService {

    private final CaseLoader loader;
    private final CaseTypeOutcomeRepository outcomes;
    private final EntityManager entityManager;
    private final InstantSource clock;

    DefaultCaseStatusService(
            CaseLoader loader, CaseTypeOutcomeRepository outcomes, EntityManager entityManager, InstantSource clock) {
        this.loader = loader;
        this.outcomes = outcomes;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ClosableCase closable(Actor actor, String caseNumber) {
        CaseRecord record = loader.load(actor.permissions(), caseNumber, CaseAction.REQUEST_CLOSURE);
        return new ClosableCase(
                new CaseRef(
                        Objects.requireNonNull(record.getId()),
                        record.getCaseNumber(),
                        record.getTitle(),
                        record.getTeamId()),
                CaseLoader.facts(record),
                outcomes.findByIdCaseTypeIdOrderBySortOrderAsc(record.getCaseTypeId()).stream()
                        .map(CaseTypeOutcome::outcome)
                        .toList());
    }

    @Override
    public void move(Actor actor, String caseNumber, CaseAction action, CaseStatus from, CaseStatus to) {
        CaseRecord record = loader.load(actor.permissions(), caseNumber, action);
        // Re-read under the row lock: the matrix decided on the state loaded before, and another request or
        // decision may have moved the case since.
        entityManager.refresh(record, LockModeType.PESSIMISTIC_WRITE);
        if (record.getStatus() != from) {
            throw new CaseException(Kind.CONFLICT, "the case is no longer " + from);
        }
        record.moveTo(to, actor.permissions().userId(), clock.instant());
    }
}
