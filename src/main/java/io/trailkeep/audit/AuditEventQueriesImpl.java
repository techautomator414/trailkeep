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
import org.jspecify.annotations.Nullable;

/**
 * Criteria implementation of {@link AuditEventQueries}.
 *
 * <p>Scope rule: an unrestricted scope sees every event, including those with no case. Any other scope sees
 * nothing yet and so fails closed: {@code AUDIT_EVENT} has no association to {@code CASE_RECORD} (no foreign keys by
 * decision), so there is no case root to hand to {@link CaseScope#toPredicate}. WP-16 (History tab) adds an entity
 * join on {@code CASE_ID} and applies the scope to it; events with no case stay invisible to principal scopes.
 */
class AuditEventQueriesImpl implements AuditEventQueries {

    private final EntityManager entityManager;

    AuditEventQueriesImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<AuditEvent> findRecent(CaseScope scope, int limit) {
        return query(scope, limit, null);
    }

    @Override
    public List<AuditEvent> findByCase(CaseScope scope, long caseId, int limit) {
        return query(scope, limit, caseId);
    }

    private List<AuditEvent> query(CaseScope scope, int limit, @Nullable Long caseId) {
        if (!scope.isUnrestricted()) {
            return List.of();
        }
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<AuditEvent> query = cb.createQuery(AuditEvent.class);
        Root<AuditEvent> event = query.from(AuditEvent.class);
        Predicate where = caseId == null ? cb.conjunction() : cb.equal(event.get("caseId"), caseId);
        query.select(event).where(where).orderBy(cb.desc(event.get("at")), cb.desc(event.get("id")));
        return entityManager.createQuery(query).setMaxResults(limit).getResultList();
    }
}
