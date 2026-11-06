// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.updaterequest;

import io.trailkeep.casefile.CaseScopeService;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.util.List;

/**
 * Criteria implementation of {@link UpdateRequestQueries}. A request holds its case as an id; {@link
 * CaseScopeService} joins or subqueries the case entity and applies the scope, so that entity never leaves
 * {@code casefile}.
 */
class UpdateRequestQueriesImpl implements UpdateRequestQueries {

    private final EntityManager entityManager;
    private final CaseScopeService scopes;

    UpdateRequestQueriesImpl(EntityManager entityManager, CaseScopeService scopes) {
        this.entityManager = entityManager;
        this.scopes = scopes;
    }

    @Override
    public List<UpdateRequest> findForCase(CaseScope scope, CaseNumber caseNumber) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<UpdateRequest> query = cb.createQuery(UpdateRequest.class);
        Root<UpdateRequest> request = query.from(UpdateRequest.class);
        query.select(request)
                .where(scopes.readableCase(scope, caseNumber, request.get("caseId"), query, cb))
                .orderBy(cb.desc(request.get("requestedAt")), cb.desc(request.get("id")));
        return entityManager.createQuery(query).getResultList();
    }

    @Override
    public List<UpdateRequest> findOpenFrom(CaseScope scope, long recipientId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<UpdateRequest> query = cb.createQuery(UpdateRequest.class);
        Root<UpdateRequest> request = query.from(UpdateRequest.class);
        query.select(request)
                .where(
                        cb.equal(request.get("requestedFrom"), recipientId),
                        cb.isNull(request.get("answeredAt")),
                        scopes.readableCases(scope, request.get("caseId"), query, cb))
                .orderBy(cb.desc(request.get("requestedAt")), cb.desc(request.get("id")));
        return entityManager.createQuery(query).setMaxResults(MAX_OPEN).getResultList();
    }
}
