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
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * The portable v1 {@link SearchGateway}: {@code CASE_ID IN (SELECT … FROM CASE_SEARCH WHERE SEARCH_TEXT LIKE ?
 * ESCAPE '\' AND …)}, one bound pattern per word, or the case number containing the text. {@code SEARCH_TEXT} is
 * stored lower-cased (see {@link CaseSearch}) and the words are lower-cased here, so no function is applied to the
 * {@code CLOB}. It is a scan on both vendors, which ADR-0006 accepts at 50k cases. Criteria, not native SQL: JPA
 * expresses {@code LIKE} on the {@code CLOB}/{@code text} column on both vendors, and only a predicate lets the
 * scope filter in the same statement. {@code %}, {@code _} and the escape character in the text match themselves.
 */
@Component
class LikeSearchGateway implements SearchGateway {

    /** Words beyond this many are ignored, which only widens the result. */
    static final int MAX_WORDS = 10;

    private static final char ESCAPE = '\\';

    @Override
    public Predicate matches(String text, Root<CaseRecord> caseRoot, CriteriaQuery<?> query, CriteriaBuilder cb) {
        Subquery<Long> matching = query.subquery(Long.class);
        Root<CaseSearch> search = matching.from(CaseSearch.class);
        Expression<String> haystack = search.<String>get("text");
        Predicate[] words = Arrays.stream(text.split("\\s+"))
                .map(word -> word.toLowerCase(Locale.ROOT))
                .distinct()
                .limit(MAX_WORDS)
                .map(word -> cb.like(haystack, contains(word), ESCAPE))
                .toArray(Predicate[]::new);
        matching.select(search.<Long>get("caseId")).where(words);
        return cb.or(
                cb.like(cb.upper(caseRoot.<String>get("caseNumber")), contains(text.toUpperCase(Locale.ROOT)), ESCAPE),
                caseRoot.get("id").in(matching));
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
