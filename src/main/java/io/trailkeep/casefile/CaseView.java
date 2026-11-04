// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * A case as its page shows it (BUILD_PLAN §4.3). Dates are already in {@code app.timezone}. Subject and description
 * are possible PHI: render them, never log them.
 *
 * @param number the case number
 * @param title the title
 * @param status the status
 * @param sensitivity the sensitivity
 * @param priority the priority
 * @param caseTypeName the case type's name
 * @param teamName the owning team's name
 * @param ownerName the owner's display name
 * @param identifiedOn the day the matter was identified
 * @param openedOn the day the case was opened
 * @param subjectText who or what is involved, if given
 * @param description the description; {@code null} only once purged
 * @param sla where the case stands against its SLA
 */
public record CaseView(
        CaseNumber number,
        String title,
        CaseStatus status,
        Sensitivity sensitivity,
        CasePriority priority,
        String caseTypeName,
        String teamName,
        String ownerName,
        LocalDate identifiedOn,
        LocalDate openedOn,
        @Nullable String subjectText,
        @Nullable String description,
        Sla sla) {

    /**
     * The SLA chip (BUILD_PLAN §4.3): the state, the due day and the whole days left (negative once overdue).
     *
     * @param state the state
     * @param dueOn the due day, or {@code null} when no SLA applies
     * @param daysLeft whole days from today to the due day, or 0 when no SLA applies
     */
    public record Sla(SlaState state, @Nullable LocalDate dueOn, long daysLeft) {}
}
