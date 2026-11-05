// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.DomainException.Kind;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The columns the cases list sorts by. This is the allow-list (Part B "Web"): each constant maps to one expression in
 * {@code CaseRecordQueriesImpl}, and {@link #parse} refuses any other name with 422, as the codebase refuses other
 * input no form could have sent. Status, case type, team and owner are not here: their stored values do not sort in
 * an order a person would expect.
 */
public enum CaseSort {
    /** By case number. */
    NUMBER(false),
    /** By title. */
    TITLE(false),
    /** By priority; ascending puts High first. */
    PRIORITY(false),
    /** By SLA due instant, soonest first; cases without one come last either way. */
    DUE(false),
    /** By opening instant, newest first. The default. */
    OPENED(true);

    private final boolean descendingFirst;

    CaseSort(boolean descendingFirst) {
        this.descendingFirst = descendingFirst;
    }

    /**
     * The direction the first click on this column sorts in.
     *
     * @return {@code true} for descending
     */
    public boolean descendingFirst() {
        return descendingFirst;
    }

    /**
     * The column a request names, case-insensitively.
     *
     * @param name the name, or {@code null} or blank for the default
     * @return the column; {@link #OPENED} by default
     * @throws CaseException {@code INVALID} for any other name
     */
    public static CaseSort parse(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return OPENED;
        }
        try {
            return valueOf(name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new CaseException(Kind.INVALID, "unknown sort column");
        }
    }
}
