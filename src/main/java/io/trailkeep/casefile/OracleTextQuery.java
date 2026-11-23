// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * How {@link OracleTextSearchGateway} asks Oracle Text for a search's words without changing what they match.
 *
 * <p>The {@code CONTEXT} index of V21 splits text into tokens at every character that is not a letter or digit and
 * knows only tokens, while the search matches each word as a substring of the text. Any run of ASCII letters and digits
 * in a word lies inside one token wherever it occurs in the text, so {@code %run%} (a token containing the run) finds
 * every case whose text contains the word: the index narrows, it never loses a match. A word that is nothing but such
 * a run is matched by the index alone; any other word, or one whose run is not used, is still matched by {@code LIKE}
 * on the cases the index found. Runs shorter than {@link #MIN_RUN} are not used: they expand to too many tokens to
 * help. If no word has a usable run, there is no index query and the search is {@code LIKE} alone.
 *
 * <p>Nothing the person typed reaches Oracle Text but those runs ({@code [a-z0-9]}), each between two {@code %}
 * wildcards and joined by {@code AND}, so no operator, reserved word or special character in a search can be read as
 * query syntax. Where Oracle Text keeps only the first 255 bytes of a token, a run past them is not found; that is
 * the one known difference from {@code LIKE} (ADR-0006, amended 2026-11-23).
 *
 * @param contains the {@code CONTAINS} query, empty when the index cannot help
 * @param likeWords the words still matched with {@code LIKE}
 */
record OracleTextQuery(String contains, List<String> likeWords) {

    /** The shortest run of letters and digits worth an index lookup. */
    static final int MIN_RUN = 3;

    /** The most runs one search asks the index for: each costs a lookup, and the rest are matched by {@code LIKE}. */
    static final int MAX_TERMS = 3;

    /** Longer runs are cut to this length and their word is checked by {@code LIKE}. */
    static final int MAX_TERM_LENGTH = 64;

    private static final Pattern RUN = Pattern.compile("[a-z0-9]+");

    OracleTextQuery {
        likeWords = List.copyOf(likeWords);
    }

    /**
     * The query for a search's words.
     *
     * @param words the words, as {@link LikeSearchGateway#words} gives them
     * @return the query
     */
    static OracleTextQuery of(List<String> words) {
        List<Term> terms = new ArrayList<>();
        for (String word : words) {
            String run = longestRun(word);
            if (run.length() >= MIN_RUN) {
                terms.add(new Term(word, run));
            }
        }
        List<Term> used = terms.stream()
                .sorted(Comparator.comparingInt((Term term) -> term.run().length())
                        .reversed())
                .limit(MAX_TERMS)
                .toList();
        List<String> likeWords = words.stream()
                .filter(word -> used.stream().noneMatch(term -> term.word().equals(word) && term.exact()))
                .toList();
        String contains =
                used.stream().map(term -> '%' + term.indexed() + '%').distinct().collect(Collectors.joining(" AND "));
        return new OracleTextQuery(contains, likeWords);
    }

    private static String longestRun(String word) {
        String longest = "";
        Matcher run = RUN.matcher(word);
        while (run.find()) {
            if (run.group().length() > longest.length()) {
                longest = run.group();
            }
        }
        return longest;
    }

    /** A word and its longest run of letters and digits. */
    private record Term(String word, String run) {

        /** The word is the run, and short enough to be asked for whole. */
        boolean exact() {
            return word.equals(run) && run.length() <= MAX_TERM_LENGTH;
        }

        String indexed() {
            return run.length() <= MAX_TERM_LENGTH ? run : run.substring(0, MAX_TERM_LENGTH);
        }
    }
}
