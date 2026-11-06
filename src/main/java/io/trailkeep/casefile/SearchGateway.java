// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * Case search behind one seam (ADR-0006): v1 matches {@code CASE_SEARCH} with {@code LIKE} ({@link
 * LikeSearchGateway}); vendor adapters ({@code pg_trgm}, Oracle Text) may replace it in v1.1. An adapter contributes
 * a predicate on the case root of the caller's query, so the candidates it finds are filtered by the same {@code
 * CaseScope} predicate in the same statement, and a count over a search is exact.
 */
interface SearchGateway {

    /**
     * A predicate true for cases whose number contains the text, or whose search text (title, description, comments;
     * never the subject) contains every word of it, ignoring case.
     *
     * @param text the search text, stripped and not blank
     * @param caseRoot the {@code CaseRecord} root of the query
     * @param query the query being built, for subqueries
     * @param cb the criteria builder
     * @return the predicate
     */
    Predicate matches(String text, Root<CaseRecord> caseRoot, CriteriaQuery<?> query, CriteriaBuilder cb);
}
