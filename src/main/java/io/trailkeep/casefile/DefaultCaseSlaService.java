// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The evaluator's view of {@code CASE_SLA}; see {@link CaseSlaService}. JPQL throughout: the row lock carries no row
 * limit (which is what Oracle cannot lock, BUILD_PLAN §6 on the outbox), so no native SQL is needed.
 */
@Service
class DefaultCaseSlaService implements CaseSlaService {

    private static final String DUE = "SELECT s.caseId FROM CaseSla s"
            + " WHERE (s.state = :onTrack AND s.atRiskAt <= :now) OR (s.state = :atRisk AND s.dueAt <= :now)"
            + " ORDER BY s.caseId";
    private static final String LOCK = "SELECT s FROM CaseSla s WHERE s.caseId IN :ids ORDER BY s.caseId";
    private static final String MOVE = "UPDATE CaseSla s SET s.state = :to, s.lastEvaluatedAt = :now"
            + " WHERE s.caseId IN :ids AND s.state = :from";

    private final EntityManager entityManager;

    DefaultCaseSlaService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> dueForEvaluation(Instant now) {
        return entityManager
                .createQuery(DUE, Long.class)
                .setParameter("onTrack", SlaState.ON_TRACK)
                .setParameter("atRisk", SlaState.AT_RISK)
                .setParameter("now", now)
                .getResultList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SlaTransition> advance(Collection<Long> caseIds, Instant now) {
        if (caseIds.isEmpty()) {
            return List.of();
        }
        List<CaseSla> rows = entityManager
                .createQuery(LOCK, CaseSla.class)
                .setParameter("ids", caseIds)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList();
        List<SlaTransition> transitions = new ArrayList<>(rows.size());
        Map<Move, List<Long>> moves = new LinkedHashMap<>();
        for (CaseSla row : rows) {
            SlaState to = row.next(now);
            if (to != null) {
                transitions.add(new SlaTransition(row.getCaseId(), row.getState(), to, row.getCycle(), row.getDueAt()));
                moves.computeIfAbsent(new Move(row.getState(), to), move -> new ArrayList<>())
                        .add(row.getCaseId());
            }
            // The bulk updates below bypass the persistence context; nothing in this transaction may see a stale row.
            entityManager.detach(row);
        }
        moves.forEach((move, ids) -> entityManager
                .createQuery(MOVE)
                .setParameter("from", move.from())
                .setParameter("to", move.to())
                .setParameter("now", now)
                .setParameter("ids", ids)
                .executeUpdate());
        return transitions;
    }

    /** One kind of change, written by one bulk update. */
    private record Move(SlaState from, SlaState to) {}
}
