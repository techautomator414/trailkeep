// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import io.trailkeep.audit.AuditTrailService.StandingEvent;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Which case-less events still stand (WP-25): an entity's latest event among an action and the actions that clear it
 * is that action. "Latest" is by {@code AT_TS}, then by {@code ID} for events with the same time: ids come from a
 * pooled sequence, so two replicas hand them out in blocks and an id says nothing about time across replicas, while
 * within one transaction (a sign-in that records {@code LOGIN} and then {@code ROLE_CONFLICT}) they do increase.
 * Only events with no case are read; nothing case-rooted passes through here, so it needs no {@code CaseScope}.
 */
@Component
class StandingEventQueries {

    private static final String STANDING = "SELECT e.entityId, e.at FROM AuditEvent e"
            + " WHERE e.caseId IS NULL AND e.entityType = :type AND e.action = :action AND e.entityId IS NOT NULL"
            + " AND NOT EXISTS (SELECT l.id FROM AuditEvent l WHERE l.caseId IS NULL AND l.entityType = :type"
            + " AND l.entityId = e.entityId AND l.action IN :actions"
            + " AND (l.at > e.at OR (l.at = e.at AND l.id > e.id)))"
            + " ORDER BY e.at, e.id";

    private final EntityManager entityManager;

    StandingEventQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    List<StandingEvent> standing(String entityType, String action, Set<String> clearedBy) {
        Set<String> actions = new HashSet<>(clearedBy);
        actions.add(action);
        return entityManager
                .createQuery(STANDING, Object[].class)
                .setParameter("type", entityType)
                .setParameter("action", action)
                .setParameter("actions", actions)
                .getResultList()
                .stream()
                .map(row -> new StandingEvent((Long) row[0], (Instant) row[1]))
                .toList();
    }
}
