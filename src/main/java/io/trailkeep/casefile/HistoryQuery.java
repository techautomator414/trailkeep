// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.DomainException.Kind;
import java.util.Locale;

/**
 * Which part of a case's history is asked for: a kind and a page. Read from the query string by {@link #parse}, which
 * answers 422 for a kind no form offers and for a page that is not a positive whole number.
 *
 * @param kind the kind of event to keep
 * @param page the page, from 1
 */
public record HistoryQuery(HistoryKind kind, int page) {

    /**
     * Reads the query string's {@code kind} and {@code page}.
     *
     * @param kind the kind, in either case, such as {@code comments}
     * @param page the page number as text
     * @return the query
     * @throws CaseException {@code INVALID} for an unknown kind or a page that is not a positive integer
     */
    public static HistoryQuery parse(String kind, String page) {
        HistoryKind chosen;
        try {
            chosen = HistoryKind.valueOf(kind.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new CaseException(Kind.INVALID, "no such kind of event");
        }
        try {
            int number = Integer.parseInt(page);
            if (number >= 1) {
                return new HistoryQuery(chosen, number);
            }
        } catch (NumberFormatException malformed) {
            // Falls through to the refusal below.
        }
        throw new CaseException(Kind.INVALID, "pages are numbered from 1");
    }
}
