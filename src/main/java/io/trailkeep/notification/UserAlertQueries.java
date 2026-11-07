// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

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
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * A person's alerts as the person's {@link CaseScope} reads them: an alert about no case is always listed, and an
 * alert about a case is listed only while the scope reads that case. {@code USER_ALERT} has no association to {@code
 * CASE_RECORD} and {@code notification} may not depend on {@code casefile}, so the case is reached the way {@code
 * audit} reaches it (#86): a subquery on the entity the case module registers as {@value #CASE_ENTITY}, with {@link
 * CaseScope#toPredicate} applied to it. If that entity is not registered the query fails closed and lists only the
 * alerts with no case. An unrestricted scope lists everything.
 */
@Component
class UserAlertQueries {

    /** The name of the case entity, the one thing this module knows about it. */
    static final String CASE_ENTITY = "CaseRecord";

    private final EntityManager entityManager;

    UserAlertQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * How many alerts of the person the scope lists.
     *
     * @param scope the person's scope
     * @param userId the person
     * @param unreadOnly whether to count only the alerts not marked read
     * @return the count
     */
    long count(CaseScope scope, long userId, boolean unreadOnly) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<UserAlert> alert = query.from(UserAlert.class);
        CriteriaQuery<Long> counting =
                query.select(cb.count(alert)).where(where(scope, userId, unreadOnly, query, alert, cb));
        return entityManager.createQuery(counting).getSingleResult();
    }

    /**
     * One page of the person's listed alerts, newest first.
     *
     * @param scope the person's scope
     * @param userId the person
     * @param offset how many to skip
     * @param limit how many at most
     * @return the alerts
     */
    List<UserAlert> page(CaseScope scope, long userId, int offset, int limit) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<UserAlert> query = cb.createQuery(UserAlert.class);
        Root<UserAlert> alert = query.from(UserAlert.class);
        query.select(alert)
                .where(where(scope, userId, false, query, alert, cb))
                .orderBy(cb.desc(alert.get("createdAt")), cb.desc(alert.get("id")));
        return entityManager
                .createQuery(query)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }

    private Predicate[] where(
            CaseScope scope,
            long userId,
            boolean unreadOnly,
            CriteriaQuery<?> query,
            Root<UserAlert> alert,
            CriteriaBuilder cb) {
        List<Predicate> all = new ArrayList<>(3);
        all.add(cb.equal(alert.get("userId"), userId));
        if (unreadOnly) {
            all.add(cb.isNull(alert.get("readAt")));
        }
        if (!scope.isUnrestricted()) {
            Predicate noCase = cb.isNull(alert.get("caseId"));
            EntityType<?> caseEntity = caseEntity();
            if (caseEntity == null) {
                all.add(noCase);
            } else {
                Subquery<Long> readable = query.subquery(Long.class);
                Root<?> caseRoot = readable.from(caseEntity);
                readable.select(caseRoot.<Long>get("id")).where(scope.toPredicate(caseRoot, query, cb));
                all.add(cb.or(noCase, alert.<Long>get("caseId").in(readable)));
            }
        }
        return all.toArray(Predicate[]::new);
    }

    private @Nullable EntityType<?> caseEntity() {
        return entityManager.getMetamodel().getEntities().stream()
                .filter(entity -> CASE_ENTITY.equals(entity.getName()))
                .findFirst()
                .orElse(null);
    }
}
