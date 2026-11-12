// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;

/**
 * A source query failed (design §4.1, §10). Driver messages quote values (PostgreSQL's {@code Key (x)=(…)}, Oracle's
 * column values), so the exception carries an issue code derived from the type and SQLState only, and neither the
 * driver's message nor the driver's exception as its cause: nothing it holds can reach a log or {@code IMPORT_ISSUE}.
 */
final class SourceQueryException extends SourceException {

    private static final long serialVersionUID = 1L;

    private SourceQueryException(String code) {
        super(code, null);
    }

    /**
     * The exception for a driver failure, by type and SQLState only.
     *
     * @param failure what the driver threw; only its type and SQLState are read
     * @return the code-only exception
     */
    static SourceQueryException of(SQLException failure) {
        String state = failure.getSQLState() == null ? "" : failure.getSQLState();
        String code;
        if (failure instanceof SQLTimeoutException || "57014".equals(state)) {
            code = "SOURCE_TIMEOUT";
        } else if ("42501".equals(state) || failure.getErrorCode() == 1031 || failure.getErrorCode() == 942) {
            code = "SOURCE_PERMISSION_DENIED";
        } else if (state.startsWith("08") || state.startsWith("28")) {
            code = "SOURCE_UNAVAILABLE";
        } else {
            code = "SOURCE_QUERY_FAILED";
        }
        return new SourceQueryException(code);
    }
}
