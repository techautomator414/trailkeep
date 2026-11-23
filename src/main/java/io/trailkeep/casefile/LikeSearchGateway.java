// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * The portable {@link SearchGateway}: {@code CASE_ID IN (SELECT … FROM CASE_SEARCH WHERE SEARCH_TEXT LIKE ? ESCAPE
 * '\' AND …)}, one bound pattern per word, or the case number containing the text. {@code SEARCH_TEXT} is stored
 * lower-cased (see {@link CaseSearch}) and the words are lower-cased here, so no function is applied to the {@code
 * CLOB}. Criteria, not native SQL: JPA expresses {@code LIKE} on the {@code CLOB}/{@code text} column on both vendors,
 * and only a predicate lets the scope filter in the same statement. {@code %}, {@code _} and the escape character in
 * the text match themselves. On PostgreSQL the patterns are answered from the trigram index of V21; on Oracle it is a
 * scan, and {@link OracleTextSearchGateway} narrows it with Oracle Text (ADR-0017).
 */
class LikeSearchGateway implements SearchGateway {

    /** Words beyond this many are ignored, which only widens the result. */
    static final int MAX_WORDS = 10;

    private static final char ESCAPE = '\\';

    @Override
    public Predicate matches(String text, Root<CaseRecord> caseRoot, CriteriaQuery<?> query, CriteriaBuilder cb) {
        Subquery<Long> matching = query.subquery(Long.class);
        Root<CaseSearch> search = matching.from(CaseSearch.class);
        matching.select(search.<Long>get("caseId"))
                .where(containsEach(words(text), search.get("text"), cb).toArray(Predicate[]::new));
        return cb.or(numberContains(text, caseRoot, cb), caseRoot.get("id").in(matching));
    }

    /**
     * The words every case's search text must contain: the text split at white space, lower-cased like the stored
     * text, without repeats, at most {@link #MAX_WORDS}. Every adapter matches these words, so they agree on what a
     * search means.
     *
     * @param text the search text, stripped and not blank
     * @return the words, in the order written
     */
    static List<String> words(String text) {
        return Arrays.stream(text.split("\\s+"))
                .map(CaseSearch::normalized)
                .distinct()
                .limit(MAX_WORDS)
                .toList();
    }

    /**
     * The case number containing the text, ignoring case.
     *
     * @param text the search text
     * @param caseRoot the {@code CaseRecord} root of the query
     * @param cb the criteria builder
     * @return the predicate
     */
    static Predicate numberContains(String text, Root<CaseRecord> caseRoot, CriteriaBuilder cb) {
        return cb.like(cb.upper(caseRoot.<String>get("caseNumber")), contains(text.toUpperCase(Locale.ROOT)), ESCAPE);
    }

    /**
     * One {@code LIKE '%word%'} per word.
     *
     * @param words lower-cased words
     * @param haystack {@code SEARCH_TEXT}
     * @param cb the criteria builder
     * @return the predicates
     */
    static List<Predicate> containsEach(Collection<String> words, Expression<String> haystack, CriteriaBuilder cb) {
        return words.stream()
                .map(word -> cb.like(haystack, contains(word), ESCAPE))
                .toList();
    }

    /** A {@code LIKE} pattern matching any value that contains the text literally. */
    static String contains(String text) {
        StringBuilder pattern = new StringBuilder(text.length() + 2).append('%');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%' || c == '_' || c == ESCAPE) {
                pattern.append(ESCAPE);
            }
            pattern.append(c);
        }
        return pattern.append('%').toString();
    }
}
