// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * Case search behind one seam (ADR-0006): {@link LikeSearchGateway} matches {@code CASE_SEARCH} with {@code LIKE},
 * which PostgreSQL answers from a trigram index ({@code pg_trgm}); on Oracle, {@link OracleTextSearchGateway} narrows
 * the same match with Oracle Text; {@code app.search.adapter} chooses ({@link SearchConfig}). An adapter contributes
 * a predicate on the case root of the caller's query (and may left-join {@code CASE_SEARCH} to it, one row a case), so
 * the candidates it finds are filtered by the same {@code CaseScope} predicate in the same statement, and a count over
 * a search is exact. Every adapter returns the same
 * cases for the same text.
 */
interface SearchGateway {

    /**
     * A predicate true for cases whose number contains the text, or whose search text (title, description, comments;
     * never the subject) contains every word of it, ignoring case.
     *
     * @param text the search text, stripped and not blank
     * @param caseRoot the {@code CaseRecord} root of the query, which the adapter may join
     * @param query the query being built, for subqueries
     * @param cb the criteria builder
     * @return the predicate
     */
    Predicate matches(String text, Root<CaseRecord> caseRoot, CriteriaQuery<?> query, CriteriaBuilder cb);
}
