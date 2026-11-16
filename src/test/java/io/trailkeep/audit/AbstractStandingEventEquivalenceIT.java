// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.RoleConflict;
import io.trailkeep.user.UserDirectoryService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The standing-conflict query after issue 141 returns exactly what it returned before, once per vendor. The query was
 * rewritten so that {@code AT_TS} is a range condition ({@code l.AT_TS >= e.AT_TS AND (l.AT_TS > e.AT_TS OR l.ID >
 * e.ID)}) instead of hiding in an {@code OR}; that is the same predicate as {@code l.AT_TS > e.AT_TS OR (l.AT_TS =
 * e.AT_TS AND l.ID > e.ID)} because {@code AT_TS} is {@code NOT NULL} (V2). The tie cases are where a mistake would
 * show: the same time with the clearing event's id above and below, in both orders, for a sign-in and for a second
 * conflict, and a later or earlier time against an id that points the other way. Both predicates are run as SQL over
 * the seeded rows, and the application's own query (through the directory) must agree with them.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class AbstractStandingEventEquivalenceIT {

    private static final long FIRST = 5_000_001L;
    private static final long LAST = 5_000_099L;
    private static final Instant T = Instant.parse("2026-03-10T12:00:00Z");

    private static final String RANGE = " AND e.ENTITY_ID BETWEEN " + FIRST + " AND " + LAST;
    private static final String HEAD = "SELECT e.ENTITY_ID, e.AT_TS FROM AUDIT_EVENT e"
            + " WHERE e.CASE_ID IS NULL AND e.ENTITY_TYPE = 'USER' AND e.ACTION = 'ROLE_CONFLICT'"
            + " AND e.ENTITY_ID IS NOT NULL" + RANGE + " AND NOT EXISTS (SELECT l.ID FROM AUDIT_EVENT l"
            + " WHERE l.CASE_ID IS NULL AND l.ENTITY_TYPE = 'USER' AND l.ENTITY_ID = e.ENTITY_ID"
            + " AND l.ACTION IN ('LOGIN', 'ROLE_CONFLICT')";
    private static final String TAIL = ") ORDER BY e.AT_TS, e.ID";
    private static final String OLD = HEAD + " AND (l.AT_TS > e.AT_TS OR (l.AT_TS = e.AT_TS AND l.ID > e.ID))" + TAIL;
    private static final String NEW = HEAD + " AND l.AT_TS >= e.AT_TS AND (l.AT_TS > e.AT_TS OR l.ID > e.ID)" + TAIL;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserDirectoryService directory;

    @Test
    void theRangeFormStandsWhereTheOrFormStoodForEveryTie() {
        // Users 2, 4 and 5 are left with a standing conflict; the others are cleared.
        event(1, 101, "ROLE_CONFLICT", T);
        event(1, 102, "LOGIN", T); // same time, higher id: cleared
        event(2, 202, "ROLE_CONFLICT", T);
        event(2, 201, "LOGIN", T); // same time, lower id: stands
        event(3, 301, "ROLE_CONFLICT", T);
        event(3, 300, "LOGIN", T.plusSeconds(1)); // later time, lower id: cleared
        event(4, 401, "ROLE_CONFLICT", T);
        event(4, 499, "LOGIN", T.minusSeconds(1)); // earlier time, higher id: stands
        event(5, 501, "ROLE_CONFLICT", T);
        event(5, 502, "ROLE_CONFLICT", T); // same time, higher id: the first is cleared, the second stands
        event(6, 600, "LOGIN", T);
        event(6, 601, "ROLE_CONFLICT", T);
        event(6, 602, "LOGIN", T); // a sign-in with a higher id after a lower one: cleared
        event(7, 701, "ROLE_CONFLICT", T);
        event(7, 702, "LOGIN", T.plus(1, ChronoUnit.DAYS)); // a day later: cleared

        List<Map<String, Object>> before = jdbc.queryForList(OLD);
        List<Map<String, Object>> after = jdbc.queryForList(NEW);

        assertThat(after)
                .as("the range form returns the rows of the OR form, in the same order")
                .isEqualTo(before);
        assertThat(after)
                .extracting(row ->
                        Objects.requireNonNull((Number) row.get("ENTITY_ID")).longValue())
                .containsExactly(FIRST + 2, FIRST + 4, FIRST + 5);
        assertThat(directory.roleConflicts().stream()
                        .map(RoleConflict::userId)
                        .filter(id -> id >= FIRST && id <= LAST)
                        .toList())
                .as("the application's own query agrees")
                .containsExactlyInAnyOrder(FIRST + 2, FIRST + 4, FIRST + 5);
    }

    private void event(int user, long id, String action, Instant at) {
        jdbc.update(
                "INSERT INTO AUDIT_EVENT (ID, AT_TS, ORIGIN, ENTITY_TYPE, ENTITY_ID, ACTION)"
                        + " VALUES (?, ?, 'UI', 'USER', ?, ?)",
                FIRST * 1_000 + id, // positive and ordered as the ids given, which the tie cases depend on
                Timestamp.from(at),
                FIRST + user,
                action);
    }
}
