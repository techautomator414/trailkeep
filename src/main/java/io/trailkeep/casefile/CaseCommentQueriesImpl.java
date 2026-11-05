// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Optional;

/**
 * Criteria implementation of {@link CaseCommentQueries}. A comment holds its case as an id, so the query adds a
 * {@code CaseRecord} root joined on that id and restricts it with {@link CaseScope#toPredicate}, in one statement.
 */
class CaseCommentQueriesImpl implements CaseCommentQueries {

    private final EntityManager entityManager;

    CaseCommentQueriesImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<CaseComment> findForCase(CaseScope scope, CaseNumber caseNumber) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<CaseComment> query = cb.createQuery(CaseComment.class);
        Root<CaseComment> comment = query.from(CaseComment.class);
        Root<CaseRecord> record = query.from(CaseRecord.class);
        query.select(comment)
                .where(
                        cb.equal(comment.get("caseId"), record.get("id")),
                        cb.equal(record.get("caseNumber"), caseNumber.toString()),
                        scope.toPredicate(record, query, cb))
                .orderBy(cb.asc(comment.get("createdAt")), cb.asc(comment.get("id")));
        return entityManager.createQuery(query).getResultList();
    }

    @Override
    public Optional<CaseComment> findOne(CaseScope scope, CaseNumber caseNumber, long commentId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<CaseComment> query = cb.createQuery(CaseComment.class);
        Root<CaseComment> comment = query.from(CaseComment.class);
        Root<CaseRecord> record = query.from(CaseRecord.class);
        query.select(comment)
                .where(
                        cb.equal(comment.get("id"), commentId),
                        cb.equal(comment.get("caseId"), record.get("id")),
                        cb.equal(record.get("caseNumber"), caseNumber.toString()),
                        scope.toPredicate(record, query, cb));
        return entityManager.createQuery(query).getResultList().stream().findFirst();
    }
}
