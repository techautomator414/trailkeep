// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A cases-list row as {@link CaseRecordQueries#list} reads it: a projection with no entity, so no subject and no
 * description are loaded. Team and owner are ids, named afterwards in one lookup each for the whole page.
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
 */
record CaseListRow(
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
        @Nullable Instant slaDueAt) {}
