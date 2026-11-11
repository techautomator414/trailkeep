// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.RequiresDocker;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;

/**
 * The durable role-conflict list, once per vendor (WP-25 part b): a user is listed while their latest {@code LOGIN} or
 * {@code ROLE_CONFLICT} audit event is the conflict, ordered by time and then by id, so a later conflict-free login
 * clears it even when another replica gave that login a smaller id, a conflict recorded after the login of the same
 * sign-in stands, an older conflict is replaced by the newer one, and events about a case or another entity type do
 * not count. Rows are written directly (negative ids, never handed out by the sequence) and removed after each test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractRoleConflictsIT {

    private static final Instant T = Instant.parse("2026-11-11T09:00:00Z");
    private static final long CLEARED = 988_001L;
    private static final long STANDING = 988_002L;
    private static final long SAME_SIGN_IN = 988_003L;
    private static final long TWICE = 988_004L;
    private static final long OTHER_TYPE = 988_005L;
    private static final Set<Long> USERS = Set.of(CLEARED, STANDING, SAME_SIGN_IN, TWICE, OTHER_TYPE);

    @Autowired
    private UserDirectoryService directory;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeRows() {
        jdbc.update("DELETE FROM AUDIT_EVENT WHERE ID < 0");
    }

    @Test
    void theLatestLoginOrConflictByTimeThenIdDecides() {
        // Replica A recorded the conflict with a high id; a later login on replica B got a lower one: cleared.
        event(-1_001, T, "USER", CLEARED, "ROLE_CONFLICT", null);
        event(-9_001, T.plusSeconds(60), "USER", CLEARED, "LOGIN", null);
        // The reverse: a login with a high id, then a later conflict with a lower one: stands.
        event(-1_002, T, "USER", STANDING, "LOGIN", null);
        event(-9_002, T.plusSeconds(60), "USER", STANDING, "ROLE_CONFLICT", null);
        // One sign-in, the same instant: LOGIN first, then ROLE_CONFLICT with the next id: stands.
        event(-5_002, T, "USER", SAME_SIGN_IN, "LOGIN", null);
        event(-5_001, T, "USER", SAME_SIGN_IN, "ROLE_CONFLICT", null);
        // Two conflicting sign-ins: listed once, with the newer time.
        event(-6_004, T, "USER", TWICE, "LOGIN", null);
        event(-6_003, T, "USER", TWICE, "ROLE_CONFLICT", null);
        event(-6_002, T.plusSeconds(120), "USER", TWICE, "LOGIN", null);
        event(-6_001, T.plusSeconds(120), "USER", TWICE, "ROLE_CONFLICT", null);
        // Not a user event, or about a case: never listed.
        event(-7_001, T, "TEAM", OTHER_TYPE, "ROLE_CONFLICT", null);
        event(-7_002, T, "USER", OTHER_TYPE, "ROLE_CONFLICT", 1L);

        assertThat(mine())
                .containsExactly(
                        new RoleConflict(SAME_SIGN_IN, T),
                        new RoleConflict(STANDING, T.plusSeconds(60)),
                        new RoleConflict(TWICE, T.plusSeconds(120)));
    }

    @Test
    void laterConflictFreeSignInClearsTheConflict() {
        event(-8_002, T, "USER", CLEARED, "LOGIN", null);
        event(-8_001, T, "USER", CLEARED, "ROLE_CONFLICT", null);
        assertThat(mine()).containsExactly(new RoleConflict(CLEARED, T));

        event(-8_000, T.plusSeconds(3600), "USER", CLEARED, "LOGIN", null);
        assertThat(mine()).isEmpty();
    }

    private List<RoleConflict> mine() {
        return directory.roleConflicts().stream()
                .filter(conflict -> USERS.contains(conflict.userId()))
                .toList();
    }

    /** One event; a less negative id is a larger one, as the sequence would hand out next. */
    private void event(long id, Instant at, String entityType, long entityId, String action, @Nullable Long caseId) {
        jdbc.update(
                "INSERT INTO AUDIT_EVENT (ID, AT_TS, ORIGIN, CASE_ID, ENTITY_TYPE, ENTITY_ID, ACTION)"
                        + " VALUES (?, ?, 'UI', ?, ?, ?, ?)",
                id,
                Timestamp.from(at),
                new SqlParameterValue(Types.BIGINT, caseId),
                entityType,
                entityId,
                action);
    }
}
