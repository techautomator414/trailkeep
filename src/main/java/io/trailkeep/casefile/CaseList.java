// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.casefile.CaseService.Choice;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import java.time.LocalDate;
import java.util.List;

/**
 * One page of the cases list (BUILD_PLAN §4.4) with what its filter drawer offers. Only cases the person's {@code
 * CaseScope} reads are counted or listed. Dates are already in {@code app.timezone}.
 *
 * @param rows the cases on this page
 * @param total how many cases match the filter, on every page
 * @param page the page shown, from 1; a requested page past the last is shown as the last
 * @param pages how many pages there are; at least 1
 * @param caseTypes the case types to filter by, by name
 * @param teams the teams the person reads cases of, by name
 * @param sensitivityFilter whether the person reads any restricted case, so the drawer offers Sensitivity
 */
public record CaseList(
        List<Row> rows,
        long total,
        int page,
        int pages,
        List<Choice> caseTypes,
        List<Choice> teams,
        boolean sensitivityFilter) {

    /** Copies the lists. */
    public CaseList {
        rows = List.copyOf(rows);
        caseTypes = List.copyOf(caseTypes);
        teams = List.copyOf(teams);
    }

    /**
     * One case as the list shows it. It holds no subject and no description.
     *
     * @param number the case number
     * @param title the title
     * @param status the status
     * @param sensitivity the sensitivity
     * @param priority the priority
     * @param caseTypeName the case type's name
     * @param teamName the owning team's name
     * @param ownerName the owner's display name
     * @param openedOn the day the case was opened
     * @param sla where the case stands against its SLA
     */
    public record Row(
            CaseNumber number,
            String title,
            CaseStatus status,
            Sensitivity sensitivity,
            CasePriority priority,
            String caseTypeName,
            String teamName,
            String ownerName,
            LocalDate openedOn,
            CaseView.Sla sla) {}
}
