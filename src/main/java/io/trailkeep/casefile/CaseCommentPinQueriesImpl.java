// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Criteria implementation of {@link CaseCommentPinQueries}. A pin holds its case as an id, so each query adds a
 * {@code CaseRecord} root joined on that id and restricts it with {@link CaseScope#toPredicate}, in one statement.
 */
class CaseCommentPinQueriesImpl implements CaseCommentPinQueries {

    private final EntityManager entityManager;

    CaseCommentPinQueriesImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<CaseCommentPin> findForCase(CaseScope scope, CaseNumber caseNumber) {
        return query(scope, caseNumber, null);
    }

    @Override
    public Optional<CaseCommentPin> findOne(CaseScope scope, CaseNumber caseNumber, long commentId) {
        return query(scope, caseNumber, commentId).stream().findFirst();
    }

    private List<CaseCommentPin> query(CaseScope scope, CaseNumber caseNumber, @Nullable Long commentId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<CaseCommentPin> query = cb.createQuery(CaseCommentPin.class);
        Root<CaseCommentPin> pin = query.from(CaseCommentPin.class);
        Root<CaseRecord> record = query.from(CaseRecord.class);
        List<Predicate> where = new ArrayList<>(List.of(
                cb.equal(pin.get("caseId"), record.get("id")),
                cb.equal(record.get("caseNumber"), caseNumber.toString()),
                scope.toPredicate(record, query, cb)));
        if (commentId != null) {
            where.add(cb.equal(pin.get("commentId"), commentId));
        }
        return entityManager
                .createQuery(query.select(pin).where(where.toArray(Predicate[]::new)))
                .getResultList();
    }
}
