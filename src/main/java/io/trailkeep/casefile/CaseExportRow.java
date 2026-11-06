// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A CSV export row as {@link CaseRecordQueries#export} reads it: the list's projection plus the subject and the
 * description, which may hold PHI and are written only when the export includes details (BUILD_PLAN §3.3).
 *
 * @param caseNumber the case number
 * @param title the title
 * @param status the status
 * @param sensitivity the sensitivity
 * @param priority the priority
 * @param caseTypeName the case type's name
 * @param teamId the owning team
 * @param ownerId the owner
 * @param openedAt when the case was opened
 * @param slaState the SLA state, or {@code null} without an SLA row
 * @param slaDueAt the SLA due instant, or {@code null}
 * @param subjectText the subject or involved parties, or {@code null}
 * @param description the description, or {@code null}
 */
record CaseExportRow(
        String caseNumber,
        String title,
        CaseStatus status,
        Sensitivity sensitivity,
        CasePriority priority,
        String caseTypeName,
        long teamId,
        long ownerId,
        Instant openedAt,
        @Nullable SlaState slaState,
        @Nullable Instant slaDueAt,
        @Nullable String subjectText,
        @Nullable String description) {

    /** Never the subject or the description (Part B "Logging"). */
    @Override
    public String toString() {
        return "CaseExportRow[" + caseNumber + "]";
    }
}
