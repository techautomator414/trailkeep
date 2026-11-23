// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;

/**
 * The Oracle {@link SearchGateway} (ADR-0006, amended 2026-11-23): the {@link LikeSearchGateway}'s predicate, with
 * {@code CONTAINS(SEARCH_TEXT, ?) > 0} on the V21 {@code CONTEXT} index added to its subquery, so the index finds the
 * candidate rows and {@code LIKE} reads only those, instead of every case's {@code CLOB}. {@link OracleTextQuery} says
 * why the results are the same. When no word has a run the index can look up, or Oracle Text would refuse the query
 * for expanding to too many tokens, the search is the {@code LIKE} gateway's, unchanged. {@code CONTAINS} is called
 * through {@link CriteriaBuilder#function}, which Hibernate renders as written, with the query bound as a parameter;
 * the predicate stays in the caller's statement, beside the scope.
 */
class OracleTextSearchGateway implements SearchGateway {

    private final LikeSearchGateway like;
    private final OracleTextJdbcDao index;

    OracleTextSearchGateway(LikeSearchGateway like, OracleTextJdbcDao index) {
        this.like = like;
        this.index = index;
    }

    @Override
    public Predicate matches(String text, Root<CaseRecord> caseRoot, CriteriaQuery<?> query, CriteriaBuilder cb) {
        OracleTextQuery textQuery = OracleTextQuery.of(LikeSearchGateway.words(text));
        if (textQuery.contains().isEmpty() || !index.answers(textQuery.contains())) {
            return like.matches(text, caseRoot, query, cb);
        }
        return LikeSearchGateway.numberOrText(text, caseRoot, query, cb, haystack -> {
            List<Predicate> all = new ArrayList<>();
            all.add(contains(haystack, textQuery.contains(), cb));
            all.addAll(LikeSearchGateway.containsEach(textQuery.likeWords(), haystack, cb));
            return all;
        });
    }

    private static Predicate contains(Expression<String> haystack, String contains, CriteriaBuilder cb) {
        return cb.greaterThan(cb.function("contains", Integer.class, haystack, cb.literal(contains)), 0);
    }
}
