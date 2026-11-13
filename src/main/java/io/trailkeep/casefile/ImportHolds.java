// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.casefile.CaseImportService.ImportHold;
import io.trailkeep.user.UserDirectoryService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The legal hold an imported case is created with (WP-26 part ad, design §7.1 "import and hold all", §8.1 R6 9), for
 * {@link DefaultCaseImportService}, in its transaction: placed by the "Trailkeep import" principal, who holds no
 * Compliance Admin role, with the fixed reason {@link ImportHold} gives, on the case before its first write, so the
 * case's INSERT carries it and the import user needs no UPDATE of a hold; then recorded as a hold placed in the app is
 * ({@link DefaultLegalHoldService}): a {@code LEGAL_HOLD_EVENT}, which the case's hold history shows, and {@code
 * LEGAL_HOLD_PLACED} in the audit trail, which its History shows, with the reason redacted.
 */
final class ImportHolds {

    private final EntityManager entityManager;
    private final AuditRecorder audit;
    private final UserDirectoryService directory;

    ImportHolds(EntityManager entityManager, AuditRecorder audit, UserDirectoryService directory) {
        this.entityManager = entityManager;
        this.audit = audit;
        this.directory = directory;
    }

    /**
     * Holds a new imported case, before it is persisted.
     *
     * @param record the case, not yet persisted
     * @param hold why it is held, or {@code null} when it is not
     * @param runId the import run, which the reason names
     * @param principal the import principal
     * @param now the load's instant
     * @return the reason recorded, or {@code null} when the case is not held
     */
    @Nullable
    String place(CaseRecord record, @Nullable ImportHold hold, long runId, long principal, Instant now) {
        if (hold == null) {
            return null;
        }
        String reason = hold.reason(runId);
        record.hold(principal, now, reason);
        return reason;
    }

    /**
     * Records a case's hold once the case has its id; nothing for a case not held.
     *
     * @param caseId the case
     * @param reason the reason {@link #place} returned
     * @param principal the import principal
     * @param now the load's instant
     */
    void record(long caseId, @Nullable String reason, long principal, Instant now) {
        if (reason == null) {
            return;
        }
        entityManager.persist(new LegalHoldEvent(caseId, LegalHoldEvent.Action.PLACED, principal, now, reason, null));
        audit.record(new AuditEntry(
                principal,
                directory.displayName(principal).orElse("Trailkeep import"),
                AuditOrigin.IMPORT,
                caseId,
                "CASE",
                caseId,
                "LEGAL_HOLD_PLACED",
                audit.diff(
                        new DefaultLegalHoldService.Change(false, ""),
                        new DefaultLegalHoldService.Change(true, reason))));
    }
}
