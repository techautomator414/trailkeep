// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import io.trailkeep.shared.access.CaseScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;

/**
 * Criteria implementation of {@link CaseImportedEventQueries}, with the scope rule of {@link AuditEventQueriesImpl}: a
 * principal's scope sees a case's imported events only if the case is in the scope, through a subquery on the case
 * entity, and sees nothing if that entity is not registered. Ordering and paging run in the database, so a case with
 * thousands of imported events is never read whole.
 */
class CaseImportedEventQueriesImpl implements CaseImportedEventQueries {

    private final EntityManager entityManager;

    CaseImportedEventQueriesImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<CaseImportedEvent> pageByCase(CaseScope scope, long caseId, int offset, int limit) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<CaseImportedEvent> query = cb.createQuery(CaseImportedEvent.class);
        Root<CaseImportedEvent> event = query.from(CaseImportedEvent.class);
        Predicate where = AuditEventQueriesImpl.ofReadableCase(
                entityManager, scope, caseId, event.<Long>get("caseId"), query, cb);
        if (where == null) {
            return List.of();
        }
        query.select(event).where(where).orderBy(cb.desc(event.get("at")), cb.desc(event.get("id")));
        return entityManager
                .createQuery(query)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }

    @Override
    public long countByCase(CaseScope scope, long caseId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<CaseImportedEvent> event = query.from(CaseImportedEvent.class);
        Predicate where = AuditEventQueriesImpl.ofReadableCase(
                entityManager, scope, caseId, event.<Long>get("caseId"), query, cb);
        if (where == null) {
            return 0;
        }
        CriteriaQuery<Long> counting = query.select(cb.count(event)).where(where);
        return entityManager.createQuery(counting).getSingleResult();
    }
}
