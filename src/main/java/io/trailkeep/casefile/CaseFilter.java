// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseStatus;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The typed filter of the cases list (BUILD_PLAN §4.4). Every criterion is an allow-listed enum, an id or bound
 * search text, so what a person types never becomes SQL. A criterion left empty does not restrict, except that
 * archived cases are listed only when the statuses name {@link CaseStatus#ARCHIVED}. The filter only narrows what
 * the person's {@code CaseScope} reads and never widens it.
 *
 * @param statuses the statuses wanted; empty for any status but archived
 * @param caseTypeId the case type, or {@code null} for any
 * @param teamId the owning team, or {@code null} for any
 * @param ownerId the owner, or {@code null} for anyone; ignored when {@code mine} is set
 * @param mine whether only the person's own cases are wanted
 * @param text words every listed case contains in its number, title, description or comments; blank for any
 */
public record CaseFilter(
        Set<CaseStatus> statuses,
        @Nullable Long caseTypeId,
        @Nullable Long teamId,
        @Nullable Long ownerId,
        boolean mine,
        String text) {

    /** The longest search text accepted, in characters. */
    public static final int MAX_TEXT = 200;

    /** Copies the statuses and strips the text. */
    public CaseFilter {
        statuses = Set.copyOf(statuses);
        text = text.strip();
    }

    /**
     * The filter that restricts nothing beyond hiding archived cases.
     *
     * @return the empty filter
     */
    public static CaseFilter none() {
        return new CaseFilter(Set.of(), null, null, null, false, "");
    }

    /** The same filter for one person: "mine" becomes that person as the owner. */
    CaseFilter forOwner(long userId) {
        return mine ? new CaseFilter(statuses, caseTypeId, teamId, userId, false, text) : this;
    }
}
