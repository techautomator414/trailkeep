// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import io.trailkeep.shared.access.CaseScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import jakarta.persistence.metamodel.EntityType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Criteria implementation of {@link AuditEventQueries}.
 *
 * <p>Scope rule: an unrestricted scope sees every event, including those with no case. A principal's scope sees the
 * events of one named case, and only if the case is in the scope: {@code AUDIT_EVENT} has no association to {@code
 * CASE_RECORD} (no foreign keys by decision, and {@code audit} may not depend on {@code casefile}), so the case is
 * reached by a subquery on the entity the case module registers as {@value #CASE_ENTITY}, which {@link
 * CaseScope#toPredicate} is applied to. If that entity is not registered the query fails closed and sees nothing.
 * Events with no case stay invisible to principal scopes, and so does the "recent events" list.
 */
class AuditEventQueriesImpl implements AuditEventQueries {

    /** The name of the case entity, the one thing this module knows about it. */
    static final String CASE_ENTITY = "CaseRecord";

    private final EntityManager entityManager;

    AuditEventQueriesImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<AuditEvent> findRecent(CaseScope scope, int limit) {
        if (!scope.isUnrestricted()) {
            return List.of();
        }
        return select(scope, null, Set.of(), 0, limit);
    }

    @Override
    public List<AuditEvent> findByCase(CaseScope scope, long caseId, int limit) {
        return select(scope, caseId, Set.of(), 0, limit);
    }

    @Override
    public List<AuditEvent> pageByCase(CaseScope scope, long caseId, Set<String> actions, int offset, int limit) {
        return select(scope, caseId, actions, offset, limit);
    }

    @Override
    public long countByCase(CaseScope scope, long caseId, Set<String> actions) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<AuditEvent> event = query.from(AuditEvent.class);
        Predicate where = where(scope, caseId, actions, query, event, cb);
        if (where == null) {
            return 0;
        }
        CriteriaQuery<Long> counting = query.select(cb.count(event)).where(where);
        return entityManager.createQuery(counting).getSingleResult();
    }

    private List<AuditEvent> select(
            CaseScope scope, @Nullable Long caseId, Set<String> actions, int offset, int limit) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<AuditEvent> query = cb.createQuery(AuditEvent.class);
        Root<AuditEvent> event = query.from(AuditEvent.class);
        Predicate where = where(scope, caseId, actions, query, event, cb);
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

    /** The filter, or {@code null} if nothing can be visible to this scope. */
    private @Nullable Predicate where(
            CaseScope scope,
            @Nullable Long caseId,
            Set<String> actions,
            CriteriaQuery<?> query,
            Root<AuditEvent> event,
            CriteriaBuilder cb) {
        List<Predicate> all = new ArrayList<>(3);
        if (caseId != null) {
            all.add(cb.equal(event.get("caseId"), caseId));
            if (!scope.isUnrestricted()) {
                EntityType<?> caseEntity = caseEntity();
                if (caseEntity == null) {
                    return null;
                }
                Subquery<Long> readable = query.subquery(Long.class);
                Root<?> caseRoot = readable.from(caseEntity);
                readable.select(caseRoot.<Long>get("id")).where(scope.toPredicate(caseRoot, query, cb));
                all.add(event.<Long>get("caseId").in(readable));
            }
        } else if (!scope.isUnrestricted()) {
            return null;
        }
        if (!actions.isEmpty()) {
            all.add(event.get("action").in(actions));
        }
        return cb.and(all.toArray(Predicate[]::new));
    }

    private @Nullable EntityType<?> caseEntity() {
        return entityManager.getMetamodel().getEntities().stream()
                .filter(entity -> CASE_ENTITY.equals(entity.getName()))
                .findFirst()
                .orElse(null);
    }
}
