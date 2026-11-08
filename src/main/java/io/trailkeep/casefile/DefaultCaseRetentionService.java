// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.shared.CaseStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Archiving; see {@link CaseRetentionService}. JPQL throughout. The due cases are found per case type, each with its
 * own cut-off computed here, so no date arithmetic runs in SQL and the scan uses {@code IX_CASE_ARCHIVE (STATUS,
 * CLOSED_AT)}. The archive is one conditional bulk update, so a concurrent reopen, which holds the case's row lock,
 * either commits first (the case is no longer closed and is skipped) or finds the case archived and is refused.
 */
@Service
class DefaultCaseRetentionService implements CaseRetentionService {

    private static final String TYPES = "SELECT t.id, t.retentionYears FROM CaseType t ORDER BY t.id";
    private static final String DUE = "SELECT r.id FROM CaseRecord r WHERE r.status = :closed AND r.closedAt <= :before"
            + " AND r.caseTypeId = :type AND r.legalHold = false";
    private static final String ARCHIVE = "UPDATE CaseRecord r SET r.status = :archived, r.archivedAt = :now,"
            + " r.purgeAfter = :purgeAfter, r.version = r.version + 1"
            + " WHERE r.id IN :ids AND r.status = :closed AND r.legalHold = false";
    /** The ids came from closed cases, and the archive job runs on one replica at a time: archived means by us. */
    private static final String ARCHIVED =
            "SELECT r.id FROM CaseRecord r WHERE r.id IN :ids AND r.status = :archived ORDER BY r.id";

    private final EntityManager entityManager;
    private final AuditRecorder audit;

    DefaultCaseRetentionService(EntityManager entityManager, AuditRecorder audit) {
        this.entityManager = entityManager;
        this.audit = audit;
    }

    /**
     * What the audit trail keeps of an archive.
     *
     * @param status the case's status
     * @param purgeAfter when the case may be purged, or {@code null} before
     */
    record Archive(CaseStatus status, String purgeAfter) {}

    @Override
    @Transactional(readOnly = true)
    public List<Long> dueForArchive(Instant now) {
        List<Long> due = new ArrayList<>();
        for (Tuple type : entityManager.createQuery(TYPES, Tuple.class).getResultList()) {
            Instant before = now.atZone(ZoneOffset.UTC)
                    .minusYears(type.get(1, Integer.class))
                    .toInstant();
            due.addAll(entityManager
                    .createQuery(DUE, Long.class)
                    .setParameter("closed", CaseStatus.CLOSED)
                    .setParameter("before", before)
                    .setParameter("type", type.get(0, Long.class))
                    .getResultList());
        }
        due.sort(null);
        return List.copyOf(due);
    }

    @Override
    @Transactional
    public int archive(Collection<Long> caseIds, Instant now, Duration grace) {
        if (caseIds.isEmpty()) {
            return 0;
        }
        Instant purgeAfter = now.plus(grace);
        entityManager
                .createQuery(ARCHIVE)
                .setParameter("archived", CaseStatus.ARCHIVED)
                .setParameter("now", now)
                .setParameter("purgeAfter", purgeAfter)
                .setParameter("ids", caseIds)
                .setParameter("closed", CaseStatus.CLOSED)
                .executeUpdate();
        List<Long> archived = entityManager
                .createQuery(ARCHIVED, Long.class)
                .setParameter("ids", caseIds)
                .setParameter("archived", CaseStatus.ARCHIVED)
                .getResultList();
        for (long caseId : archived) {
            audit.record(new AuditEntry(
                    null,
                    null,
                    AuditOrigin.SCHEDULER,
                    caseId,
                    "CASE",
                    caseId,
                    "CASE_ARCHIVED",
                    audit.diff(
                            new Archive(CaseStatus.CLOSED, ""),
                            new Archive(CaseStatus.ARCHIVED, purgeAfter.toString()))));
        }
        return archived.size();
    }
}
