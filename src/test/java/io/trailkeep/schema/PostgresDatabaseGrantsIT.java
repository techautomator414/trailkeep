// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import java.sql.SQLException;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** The grants matrix on PostgreSQL 16; runs in every build. */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
class PostgresDatabaseGrantsIT extends AbstractDatabaseGrantsIT {

    /** SQLSTATE insufficient_privilege (also raised for "must be owner of table"). */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** SQLSTATE not_null_violation. */
    private static final String NOT_NULL_VIOLATION = "23502";

    @Override
    boolean isPrivilegeDenied(SQLException e) {
        return INSUFFICIENT_PRIVILEGE.equals(e.getSQLState());
    }

    @Override
    boolean isNotNullViolation(SQLException e) {
        return NOT_NULL_VIOLATION.equals(e.getSQLState());
    }

    @Override
    String nextValue(String qualifiedSequence) {
        return "SELECT nextval('" + qualifiedSequence + "')";
    }

    @Override
    boolean seeingASequenceDrawsFromIt() {
        return false;
    }
}
