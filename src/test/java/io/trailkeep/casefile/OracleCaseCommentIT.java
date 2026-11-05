// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Comments on Oracle ({@code -Poracle-it}, both images). Proves what PostgreSQL cannot: a body over 4000 bytes
 * written to and read from a {@code CLOB} through Hibernate and through a plain JDBC string bind.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleCaseCommentIT extends AbstractCaseCommentIT {

    @Override
    String nextCommentId() {
        return "SELECT SEQ_CASE_COMMENT.NEXTVAL FROM DUAL";
    }
}
