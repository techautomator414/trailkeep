// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.perf;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** The role-conflict query at seven years of sign-ins on Oracle (issue 141). */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleRoleConflictPerfIT extends AbstractRoleConflictPerfIT {

    @Override
    String loginsSql() {
        return "INSERT INTO AUDIT_EVENT (ID, AT_TS, ACTOR_ID, ORIGIN, ENTITY_TYPE, ENTITY_ID, ACTION)"
                + " SELECT ? + n, TIMESTAMP '2019-11-16 00:00:00 +00:00' + NUMTODSINTERVAL(TRUNC(n / 2), 'DAY')"
                + " + NUMTODSINTERVAL(MOD(n, 2) * 28800 + ?, 'SECOND'), ?, 'UI', 'USER', ?, 'LOGIN'"
                + " FROM (SELECT LEVEL - 1 n FROM DUAL CONNECT BY LEVEL <= 5110)";
    }

    @Override
    String caseEventsSql() {
        return "INSERT INTO AUDIT_EVENT (ID, AT_TS, ACTOR_ID, ORIGIN, CASE_ID, ENTITY_TYPE, ENTITY_ID, ACTION)"
                + " SELECT 900000000000 + n, TIMESTAMP '2021-01-01 00:00:00 +00:00' + NUMTODSINTERVAL(n * 30, 'SECOND'),"
                + " 950000001, 'UI', 900000000 + MOD(n, 50000), 'CASE', 900000000 + MOD(n, 50000), 'CASE_UPDATED'"
                + " FROM (SELECT LEVEL - 1 n FROM DUAL CONNECT BY LEVEL <= 1000000)";
    }

    @Override
    void gatherStatistics() {
        jdbc.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, 'AUDIT_EVENT', cascade => TRUE); END;");
    }

    @Override
    List<String> plan(String sql) {
        // One connection: the plan of a statement is read from the cursor the same session just ran.
        return jdbc.execute((java.sql.Connection connection) -> {
            List<String> lines = new ArrayList<>();
            try (java.sql.Statement statement = connection.createStatement()) {
                try (java.sql.ResultSet rows =
                        statement.executeQuery(sql.replaceFirst("SELECT", "SELECT /*+ GATHER_PLAN_STATISTICS */"))) {
                    while (rows.next()) {
                        // drain
                    }
                }
                try (java.sql.ResultSet plan = statement.executeQuery(
                        "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(NULL, NULL, 'ALLSTATS LAST'))")) {
                    while (plan.next()) {
                        lines.add(plan.getString(1));
                    }
                }
            }
            return lines;
        });
    }

    @Override
    String vendor() {
        return "oracle";
    }
}
