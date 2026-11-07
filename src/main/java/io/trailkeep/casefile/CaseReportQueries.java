// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.casefile.CaseReportService.AgingRow;
import io.trailkeep.casefile.CaseReportService.ComplianceRow;
import io.trailkeep.shared.access.CaseScope;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** The reports' scoped, grouped counts over {@code CASE_RECORD}; Criteria in {@link CaseReportQueriesImpl}. */
interface CaseReportQueries {

    /**
     * Open and pending-closure cases the scope reads, counted by age and SLA state, by team and owner.
     *
     * @param scope the caller's scope
     * @param today the day ages count to
     * @return the rows, ordered by team id and owner id
     */
    List<AgingRow> aging(CaseScope scope, LocalDate today);

    /**
     * Closed cases the scope reads closed in {@code [from, until)}, met or breached, and open ones at risk or overdue
     * now, by team and case type.
     *
     * @param scope the caller's scope
     * @param from the period's first instant
     * @param until the instant after its last
     * @return the rows, ordered by team id and case type name
     */
    List<ComplianceRow> compliance(CaseScope scope, Instant from, Instant until);
}
