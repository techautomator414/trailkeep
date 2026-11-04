// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.util.Optional;

/**
 * Criteria implementation of {@link CaseRecordQueries}. Every query is one statement whose {@code WHERE} holds the
 * case number and {@link CaseScope#toPredicate}; {@code CaseRecord} has no associations, so there is no graph to
 * fetch.
 */
class CaseRecordQueriesImpl implements CaseRecordQueries {

    private final EntityManager entityManager;

    CaseRecordQueriesImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<CaseRecord> findOne(CaseScope scope, CaseNumber caseNumber) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<CaseRecord> query = cb.createQuery(CaseRecord.class);
        Root<CaseRecord> root = query.from(CaseRecord.class);
        query.select(root)
                .where(cb.equal(root.get("caseNumber"), caseNumber.toString()), scope.toPredicate(root, query, cb));
        return entityManager.createQuery(query).getResultStream().findFirst();
    }

    @Override
    public boolean exists(CaseScope scope, CaseNumber caseNumber) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<CaseRecord> root = query.from(CaseRecord.class);
        query.select(cb.count(root))
                .where(cb.equal(root.get("caseNumber"), caseNumber.toString()), scope.toPredicate(root, query, cb));
        return entityManager.createQuery(query).getSingleResult() > 0;
    }
}
