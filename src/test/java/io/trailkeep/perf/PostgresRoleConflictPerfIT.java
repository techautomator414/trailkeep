// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.perf;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import java.util.List;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** The role-conflict query at seven years of sign-ins on PostgreSQL (issue 141). */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
class PostgresRoleConflictPerfIT extends AbstractRoleConflictPerfIT {

    @Override
    String loginsSql() {
        return "INSERT INTO AUDIT_EVENT (ID, AT_TS, ACTOR_ID, ORIGIN, ENTITY_TYPE, ENTITY_ID, ACTION)"
                + " SELECT ? + g, TIMESTAMPTZ '2019-11-16 00:00:00+00' + ((g / 2) * INTERVAL '1 day')"
                + " + ((g % 2) * INTERVAL '8 hours') + (? * INTERVAL '1 second'), ?, 'UI', 'USER', ?, 'LOGIN'"
                + " FROM generate_series(0, 5109) g";
    }

    @Override
    String caseEventsSql() {
        return "INSERT INTO AUDIT_EVENT (ID, AT_TS, ACTOR_ID, ORIGIN, CASE_ID, ENTITY_TYPE, ENTITY_ID, ACTION)"
                + " SELECT 900000000000 + g, TIMESTAMPTZ '2021-01-01 00:00:00+00' + (g * INTERVAL '30 seconds'),"
                + " 950000001, 'UI', 900000000 + (g % 50000), 'CASE', 900000000 + (g % 50000), 'CASE_UPDATED'"
                + " FROM generate_series(0, 999999) g";
    }

    @Override
    void gatherStatistics() {
        jdbc.execute("ANALYZE AUDIT_EVENT");
    }

    @Override
    List<String> plan(String sql) {
        return jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + sql, String.class);
    }

    @Override
    String vendor() {
        return "postgresql";
    }
}
