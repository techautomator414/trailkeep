// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.casefile.CaseService.Actor;

/**
 * The cases list (BUILD_PLAN §4.4). Counting and listing go through the actor's {@code CaseScope} with one predicate,
 * so a case the actor may not read is missing from the rows and from the count alike, exactly as if it did not exist
 * (ADR-0005).
 */
public interface CaseListService {

    /** Cases per page. */
    int PAGE_SIZE = 25;

    /**
     * One page of the cases the actor reads that match the query, in a constant number of statements however many
     * rows, owners or teams it shows. A page past the last shows the last.
     *
     * @param actor the actor
     * @param query the filter, order and page, as {@link CaseListQuery#parse} read it
     * @return the page; empty, not an error, when nothing matches or the actor reads nothing
     */
    CaseList list(Actor actor, CaseListQuery query);

    /**
     * How many cases the actor reads match the filter: one scoped count, as the list's total (the dashboard's tiles).
     *
     * @param actor the actor
     * @param filter the filter; "mine" is the actor as owner
     * @return the count
     */
    long count(Actor actor, CaseFilter filter);
}
