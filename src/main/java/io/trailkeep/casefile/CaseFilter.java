// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import java.time.LocalDate;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The typed filter of the cases list (BUILD_PLAN §4.4). Every criterion is an allow-listed enum, an id or a date, so
 * what a person types reaches a query only as a bound value. A criterion left empty does not restrict. The filter
 * only narrows what the person's {@code CaseScope} reads and never widens it. Saved views will bind their JSON to
 * this record.
 *
 * @param statuses the statuses wanted; empty for any
 * @param includeArchived whether archived cases are listed; they are hidden unless this is set
 * @param slaStates the SLA states wanted; empty for any
 * @param caseTypeId the case type, or {@code null} for any
 * @param teamId the owning team, or {@code null} for any
 * @param ownerId the owner, or {@code null} for anyone
 * @param priority the priority, or {@code null} for any
 * @param sensitivity the sensitivity, or {@code null} for any
 * @param identifiedFrom the earliest identified-on day, inclusive, or {@code null}
 * @param identifiedTo the latest identified-on day, inclusive, or {@code null}
 */
public record CaseFilter(
        Set<CaseStatus> statuses,
        boolean includeArchived,
        Set<SlaState> slaStates,
        @Nullable Long caseTypeId,
        @Nullable Long teamId,
        @Nullable Long ownerId,
        @Nullable CasePriority priority,
        @Nullable Sensitivity sensitivity,
        @Nullable LocalDate identifiedFrom,
        @Nullable LocalDate identifiedTo) {

    private static final CaseFilter NONE =
            new CaseFilter(Set.of(), false, Set.of(), null, null, null, null, null, null, null);

    /** Copies the sets. */
    public CaseFilter {
        statuses = Set.copyOf(statuses);
        slaStates = Set.copyOf(slaStates);
    }

    /**
     * The filter that restricts nothing beyond hiding archived cases.
     *
     * @return the empty filter
     */
    public static CaseFilter none() {
        return NONE;
    }

    /**
     * Whether any criterion is set, so the page can open its filter drawer.
     *
     * @return {@code true} unless this equals {@link #none()}
     */
    public boolean narrows() {
        return !equals(NONE);
    }
}
