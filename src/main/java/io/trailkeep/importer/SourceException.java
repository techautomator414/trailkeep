// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import org.jspecify.annotations.Nullable;

/**
 * A source cannot be read soundly (design §4.2, §10): a missing or changed file, a manifest that does not match, a
 * line that is not one flat JSON object. Parser and decoder messages quote the data, so this carries an issue code and
 * at most a number (a line number, say), and never the underlying exception as its cause: nothing it holds can reach
 * a log or {@code IMPORT_ISSUE}. {@code extract} records it as a {@code FATAL} issue and stops; a failed source query
 * ({@link SourceQueryException}) is one too.
 */
class SourceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    private final @Nullable Long detail;

    /**
     * A source failure.
     *
     * @param code the issue code, for example {@code MANIFEST_MISMATCH}
     * @param detail a number that locates it, such as a line number; {@code null} without one
     */
    SourceException(String code, @Nullable Long detail) {
        super(code);
        this.code = code;
        this.detail = detail;
    }

    /**
     * The issue code.
     *
     * @return the code
     */
    String code() {
        return code;
    }

    /**
     * The number that locates the failure.
     *
     * @return it, or {@code null}
     */
    @Nullable
    Long detail() {
        return detail;
    }
}
