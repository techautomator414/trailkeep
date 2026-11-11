// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import java.sql.SQLException;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** The grants matrix on Oracle; runs only under {@code -Poracle-it}, once per image (see
 * {@link OracleSchemaIT}). */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleDatabaseGrantsIT extends AbstractDatabaseGrantsIT {

    /**
     * ORA-00942 table or view does not exist (no privilege at all on the object), ORA-01031 insufficient privileges
     * (some privilege, not this one), ORA-02289 sequence does not exist (no privilege on the sequence).
     */
    private static final Set<Integer> DENIED = Set.of(942, 1031, 2289);

    /** Oracle 23ai reports a missing object privilege as ORA-41900 and neighbours ("missing ... privilege"). */
    private static final int MISSING_PRIVILEGE_FIRST = 41900;

    private static final int MISSING_PRIVILEGE_LAST = 41999;

    /** ORA-01400 cannot insert NULL. */
    private static final int CANNOT_INSERT_NULL = 1400;

    @Override
    boolean isPrivilegeDenied(SQLException e) {
        int code = e.getErrorCode();
        return DENIED.contains(code) || (code >= MISSING_PRIVILEGE_FIRST && code <= MISSING_PRIVILEGE_LAST);
    }

    @Override
    boolean isNotNullViolation(SQLException e) {
        return e.getErrorCode() == CANNOT_INSERT_NULL;
    }

    @Override
    String nextValue(String qualifiedSequence) {
        return "SELECT " + qualifiedSequence + ".NEXTVAL FROM DUAL";
    }

    @Override
    boolean seeingASequenceDrawsFromIt() {
        return true;
    }
}
