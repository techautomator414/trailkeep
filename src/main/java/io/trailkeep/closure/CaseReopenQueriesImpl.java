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
 * Criteria implementation of {@link CaseReopenQueries}, as {@link ClosureRequestQueriesImpl}: the reopen holds its case
 * as an id and {@link CaseScopeService#readableCase} joins the case entity and applies the scope.
 */
class CaseReopenQueriesImpl implements CaseReopenQueries {

    private final EntityManager entityManager;
    private final CaseScopeService scopes;

    CaseReopenQueriesImpl(EntityManager entityManager, CaseScopeService scopes) {
        this.entityManager = entityManager;
        this.scopes = scopes;
    }

    @Override
    public List<CaseReopen> findForCase(CaseScope scope, CaseNumber caseNumber) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<CaseReopen> query = cb.createQuery(CaseReopen.class);
        Root<CaseReopen> reopen = query.from(CaseReopen.class);
        query.select(reopen)
                .where(scopes.readableCase(scope, caseNumber, reopen.get("caseId"), query, cb))
                .orderBy(cb.desc(reopen.get("reopenedAt")), cb.desc(reopen.get("id")));
        return entityManager.createQuery(query).getResultList();
    }
}
