// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Set;

/**
 * A source query failed (design §4.1, §10). Driver messages quote values (PostgreSQL's {@code Key (x)=(…)}, Oracle's
 * column values), so the exception carries an issue code derived from the type, SQLState and error number only, and
 * neither the driver's message nor the driver's exception as its cause: nothing it holds can reach a log or {@code
 * IMPORT_ISSUE}.
 */
final class SourceQueryException extends SourceException {

    private static final long serialVersionUID = 1L;

    /**
     * Vendor error numbers for a missing privilege: Oracle ORA-01031 and ORA-00942 (which Oracle answers for a table
     * the account may not see); SQL Server 229 (object), 230 (column) and 262 (statement), whose SQLStates are not
     * {@code 42501}.
     */
    private static final Set<Integer> DENIED = Set.of(1031, 942, 229, 230, 262);

    /** SQL Server's refused login, whose SQLState is not a connection class. */
    private static final int SQLSERVER_LOGIN_FAILED = 18456;

    private SourceQueryException(String code) {
        super(code, null);
    }

    /**
     * The exception for a driver failure, by type, SQLState and error number only.
     *
     * @param failure what the driver threw; only its type, SQLState and error number are read
     * @return the code-only exception
     */
    static SourceQueryException of(SQLException failure) {
        String state = failure.getSQLState() == null ? "" : failure.getSQLState();
        String code;
        if (failure instanceof SQLTimeoutException || "57014".equals(state)) {
            code = "SOURCE_TIMEOUT";
        } else if ("42501".equals(state) || DENIED.contains(failure.getErrorCode())) {
            code = "SOURCE_PERMISSION_DENIED";
        } else if (state.startsWith("08")
                || state.startsWith("28")
                || failure.getErrorCode() == SQLSERVER_LOGIN_FAILED) {
            code = "SOURCE_UNAVAILABLE";
        } else {
            code = "SOURCE_QUERY_FAILED";
        }
        return new SourceQueryException(code);
    }
}
