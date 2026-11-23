// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;

/**
 * The Oracle {@link SearchGateway} (ADR-0017): the {@link LikeSearchGateway}'s match, with {@code CONTAINS(SEARCH_TEXT,
 * ?) > 0} on the V21 {@code CONTEXT} index in front of it, so the index finds the candidate rows and {@code LIKE} reads
 * only those, instead of every case's {@code CLOB}. {@link OracleTextQuery} says why the results are the same. When no
 * word has a run the index can look up, or Oracle Text would refuse the query for expanding to too many tokens, the
 * search is the {@code LIKE} gateway's, unchanged. {@code CONTAINS} is called through {@link CriteriaBuilder#function},
 * which Hibernate renders as written, with the query bound as a parameter; the join and the predicate stay in the
 * caller's statement, beside the scope.
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
        // A left join on CASE_SEARCH (one row a case, so no row is repeated and counts stay exact), not the LIKE
        // gateway's IN (subquery): Oracle 21 does not unnest a subquery under OR, and then evaluates CONTAINS row by
        // row, with no index, for every case of the scope (1.2 s at the 500k seed, against 0.4 s joined).
        Join<CaseRecord, CaseSearch> search = caseRoot.join(CaseSearch.class, JoinType.LEFT);
        Expression<String> haystack = search.get("text");
        List<Predicate> on = new ArrayList<>();
        on.add(cb.equal(search.get("caseId"), caseRoot.get("id")));
        on.add(contains(haystack, textQuery.contains(), cb));
        on.addAll(LikeSearchGateway.containsEach(textQuery.likeWords(), haystack, cb));
        search.on(on.toArray(Predicate[]::new));
        return cb.or(LikeSearchGateway.numberContains(text, caseRoot, cb), cb.isNotNull(search.get("caseId")));
    }

    /**
     * {@code CONTAINS(SEARCH_TEXT, ?) > 0}, the query bound: {@link CriteriaBuilder#literal} would be rendered into the
     * statement, a new statement (and hard parse) for every search, while Hibernate's {@code value} is a parameter.
     */
    private static Predicate contains(Expression<String> haystack, String contains, CriteriaBuilder cb) {
        Expression<String> query = ((HibernateCriteriaBuilder) cb).value(contains);
        return cb.greaterThan(cb.function("contains", Integer.class, haystack, query), 0);
    }
}
