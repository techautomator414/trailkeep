// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.casefile.CaseService.Actor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The counts behind the reports (BUILD_PLAN §4.5, WP-22), over the cases the actor reads ({@code CaseScope}: a Reader
 * or Analyst never counts a restricted case they cannot read), one grouped query each: aging by team and owner,
 * compliance by team and case type. Counts only: no case text of any kind (§3.3).
 */
public interface CaseReportService {

    /**
     * Open and pending-closure cases of one team and owner, by age since the matter was identified and by SLA state.
     *
     * @param teamId the team
     * @param ownerId the owner
     * @param upTo30 identified 0 to 30 days ago
     * @param upTo60 31 to 60 days ago
     * @param upTo90 61 to 90 days ago
     * @param over90 more than 90 days ago
     * @param onTrack on track
     * @param atRisk at risk
     * @param overdue overdue
     * @param noSla no SLA
     */
    record AgingRow(
            long teamId,
            long ownerId,
            long upTo30,
            long upTo60,
            long upTo90,
            long over90,
            long onTrack,
            long atRisk,
            long overdue,
            long noSla) {}

    /**
     * One team and case type's SLA compliance: its cases closed in a period, met or breached, and its open cases at
     * risk or overdue now.
     *
     * @param teamId the team
     * @param caseType the case type's name
     * @param met closed in the period within the SLA
     * @param breached closed in the period after it
     * @param atRisk open and at risk now
     * @param overdue open and overdue now
     */
    record ComplianceRow(long teamId, String caseType, long met, long breached, long atRisk, long overdue) {}

    /**
     * The aging counts as of {@code today}, by team and owner.
     *
     * @param actor the actor
     * @param today the day ages count to, in {@code app.timezone}
     * @return the rows, by team id and owner id
     */
    List<AgingRow> aging(Actor actor, LocalDate today);

    /**
     * The compliance counts for cases closed from {@code from} to {@code to}, both inclusive, by team and case type.
     *
     * @param actor the actor
     * @param from the first day, in {@code app.timezone}
     * @param to the last day
     * @return the rows, by team id and case type name
     */
    List<ComplianceRow> compliance(Actor actor, LocalDate from, LocalDate to);

    /**
     * One overdue open or pending-closure case for the aging report's list: never its description or subject.
     *
     * @param caseNumber the case number
     * @param title the title
     * @param ownerId the owner
     * @param dueAt when the SLA fell due
     */
    record OverdueCase(String caseNumber, String title, long ownerId, Instant dueAt) {}

    /**
     * The overdue open and pending-closure cases the actor reads, oldest due first.
     *
     * @param actor the actor
     * @param limit the most to list
     * @return the cases
     */
    List<OverdueCase> overdue(Actor actor, int limit);
}
