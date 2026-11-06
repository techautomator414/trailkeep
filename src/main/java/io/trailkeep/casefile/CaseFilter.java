// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Sensitivity;
import java.time.LocalDate;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The typed filter of the cases list (BUILD_PLAN §4.4). Every criterion is an allow-listed enum, an id, a date or
 * bound search text, so what a person types never becomes SQL. A criterion left empty does not restrict, except
 * that archived cases are listed only when the statuses name {@link CaseStatus#ARCHIVED}. The filter only narrows
 * what the person's {@code CaseScope} reads and never widens it: a sensitivity of {@code RESTRICTED} asked for by
 * someone who reads no restricted case simply finds nothing.
 *
 * @param statuses the statuses wanted; empty for any status but archived
 * @param slaStates the SLA states wanted, as {@code CASE_SLA} stores them; empty for any
 * @param priority the priority, or {@code null} for any
 * @param sensitivity the sensitivity, or {@code null} for any
 * @param identifiedFrom the first day identified, inclusive, or {@code null} for no lower bound
 * @param identifiedTo the last day identified, inclusive, or {@code null} for no upper bound
 * @param caseTypeId the case type, or {@code null} for any
 * @param teamId the owning team, or {@code null} for any
 * @param ownerId the owner, or {@code null} for anyone; ignored when {@code mine} is set
 * @param mine whether only the person's own cases are wanted
 * @param text words every listed case contains in its number, title, description or comments; blank for any
 * @param numbers the case numbers selected on a page, in canonical form; empty for any (the export's "selected rows")
 */
public record CaseFilter(
        Set<CaseStatus> statuses,
        Set<SlaState> slaStates,
        @Nullable CasePriority priority,
        @Nullable Sensitivity sensitivity,
        @Nullable LocalDate identifiedFrom,
        @Nullable LocalDate identifiedTo,
        @Nullable Long caseTypeId,
        @Nullable Long teamId,
        @Nullable Long ownerId,
        boolean mine,
        String text,
        Set<String> numbers) {

    /** The longest search text accepted, in characters. */
    public static final int MAX_TEXT = 200;

    /** The most case numbers one selection may name: more than any page shows. */
    public static final int MAX_NUMBERS = 100;

    /**
     * Copies the sets, strips the text and refuses a date range that ends before it starts.
     *
     * @throws CaseException {@code INVALID} when {@code identifiedFrom} is after {@code identifiedTo}
     */
    public CaseFilter {
        statuses = Set.copyOf(statuses);
        slaStates = Set.copyOf(slaStates);
        numbers = Set.copyOf(numbers);
        text = text.strip();
        if (identifiedFrom != null && identifiedTo != null && identifiedFrom.isAfter(identifiedTo)) {
            throw new CaseException(Kind.INVALID, "date range ends before it starts");
        }
    }

    /**
     * The filter that restricts nothing beyond hiding archived cases.
     *
     * @return the empty filter
     */
    public static CaseFilter none() {
        return new CaseFilter(Set.of(), Set.of(), null, null, null, null, null, null, null, false, "", Set.of());
    }

    /** The same filter for one person: "mine" becomes that person as the owner. */
    CaseFilter forOwner(long userId) {
        return mine
                ? new CaseFilter(
                        statuses,
                        slaStates,
                        priority,
                        sensitivity,
                        identifiedFrom,
                        identifiedTo,
                        caseTypeId,
                        teamId,
                        userId,
                        false,
                        text,
                        numbers)
                : this;
    }
}
