// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.audit.AuditChanges;
import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.Sensitivity;
import java.util.Objects;

/** The one place that audits a read of a restricted case (BUILD_PLAN §3.3): its page, its history or a download. */
final class RestrictedReadAudit {

    /** The audit action. */
    static final String ACTION = "VIEW_RESTRICTED";

    private RestrictedReadAudit() {}

    /**
     * Records one {@code VIEW_RESTRICTED} in the caller's transaction if the case is restricted; nothing otherwise.
     *
     * @param audit the recorder
     * @param actor who reads
     * @param record the case read
     * @param changes what the read adds to the event, such as the attachment downloaded
     */
    static void record(AuditRecorder audit, Actor actor, CaseRecord record, AuditChanges changes) {
        if (record.getSensitivity() == Sensitivity.RESTRICTED) {
            long caseId = Objects.requireNonNull(record.getId());
            audit.record(new AuditEntry(
                    actor.permissions().userId(),
                    actor.displayName(),
                    AuditOrigin.UI,
                    caseId,
                    "CASE",
                    caseId,
                    ACTION,
                    changes));
        }
    }
}
