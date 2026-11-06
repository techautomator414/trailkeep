// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.closure;

import io.trailkeep.casefile.CaseScopeService;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.util.List;

/**
 * Criteria implementation of {@link ClosureRequestQueries}. A request holds its case as an id; {@link
 * CaseScopeService#readableCase} joins the case entity and applies the scope, so that entity never leaves
 * {@code casefile}.
 */
class ClosureRequestQueriesImpl implements ClosureRequestQueries {

    private final EntityManager entityManager;
    private final CaseScopeService scopes;

    ClosureRequestQueriesImpl(EntityManager entityManager, CaseScopeService scopes) {
        this.entityManager = entityManager;
        this.scopes = scopes;
    }

    @Override
    public List<ClosureRequest> findForCase(CaseScope scope, CaseNumber caseNumber) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<ClosureRequest> query = cb.createQuery(ClosureRequest.class);
        Root<ClosureRequest> request = query.from(ClosureRequest.class);
        query.select(request)
                .where(scopes.readableCase(scope, caseNumber, request.get("caseId"), query, cb))
                .orderBy(cb.desc(request.get("requestedAt")), cb.desc(request.get("id")));
        return entityManager.createQuery(query).getResultList();
    }
}
