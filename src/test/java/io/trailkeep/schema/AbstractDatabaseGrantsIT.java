// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.TestDatabaseUsers;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves the vendor {@code R__grants.sql} matrix by logging in as the runtime user and the purge user (created by
 * the test-only {@link TestDatabaseUsers}) and executing one statement per table, privilege and user. A denied
 * statement must fail with the vendor's privilege error; an allowed one must get past the privilege check. Every
 * probe is a no-op on data: {@code WHERE 1 = 0}, or an INSERT of NULL into a NOT NULL key column, which only a
 * granted user can reach (the privilege check precedes the constraint check on both vendors).
 *
 * <p>The matrix below is the specification; when a migration adds a table, add its row here and in both vendor
 * grant files.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractDatabaseGrantsIT {

    /** The DML privileges the matrix covers. */
    enum Privilege {
        SELECT,
        INSERT,
        UPDATE,
        DELETE
    }

    /** The outcome of one probe. */
    enum Outcome {
        ALLOWED,
        DENIED
    }

    /** Identity tables (V1) and the column each probe touches: the first key column. */
    static final Map<String, String> TABLES = Map.of(
            "APP_USER", "ID",
            "TEAM", "ID",
            "APP_USER_TEAM", "USER_ID",
            "APP_USER_GLOBAL_ROLE", "USER_ID");

    /** What the runtime user may do; anything absent is denied. */
    static final Map<String, Set<Privilege>> APP_USER_GRANTS = Map.of(
            "APP_USER", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE),
            "TEAM", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE),
            "APP_USER_TEAM", EnumSet.allOf(Privilege.class),
            "APP_USER_GLOBAL_ROLE", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.DELETE));

    /** What the purge user may do on these tables: nothing (it deletes case data, which arrives with V4). */
    static final Map<String, Set<Privilege>> PURGE_USER_GRANTS = Map.of();

    static final List<String> SEQUENCES = List.of("SEQ_APP_USER", "SEQ_TEAM");

    @Autowired
    private TestDatabaseUsers users;

    @Value("${app.db.app-user}")
    private String appUser = "";

    @Value("${app.db.purge-user}")
    private String purgeUser = "";

    /** One probe: who, which table, which privilege, and what the matrix says. */
    record Probe(String grantee, String table, Privilege privilege, Outcome expected) {
        @Override
        public String toString() {
            return grantee + " " + privilege + " " + table + " -> " + expected;
        }
    }

    static Stream<Probe> matrix() {
        List<Probe> probes = new ArrayList<>();
        for (String table : TABLES.keySet()) {
            for (Privilege privilege : Privilege.values()) {
                probes.add(probe("app", table, privilege, APP_USER_GRANTS));
                probes.add(probe("purge", table, privilege, PURGE_USER_GRANTS));
            }
        }
        return probes.stream();
    }

    private static Probe probe(String who, String table, Privilege privilege, Map<String, Set<Privilege>> grants) {
        boolean allowed = grants.getOrDefault(table, Set.of()).contains(privilege);
        return new Probe(who, table, privilege, allowed ? Outcome.ALLOWED : Outcome.DENIED);
    }

    /**
     * Whether an exception is the vendor's "you lack the privilege" error.
     *
     * @param e the failure
     * @return true for a privilege denial
     */
    abstract boolean isPrivilegeDenied(SQLException e);

    /**
     * Whether an exception is the vendor's NOT NULL violation, which proves an INSERT passed the privilege check.
     *
     * @param e the failure
     * @return true for a NOT NULL violation
     */
    abstract boolean isNotNullViolation(SQLException e);

    /**
     * A statement taking one value from a sequence.
     *
     * @param qualifiedSequence schema-qualified sequence name
     * @return the SQL
     */
    abstract String nextValue(String qualifiedSequence);

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    void eachTablePrivilegeIsGrantedExactlyAsTheMatrixSays(Probe probe) throws SQLException {
        String table = users.ownerSchema() + "." + probe.table();
        String column = TABLES.get(probe.table());
        String sql = switch (probe.privilege()) {
            case SELECT -> "SELECT COUNT(*) FROM " + table;
            case INSERT -> "INSERT INTO " + table + " (" + column + ") VALUES (NULL)";
            case UPDATE -> "UPDATE " + table + " SET " + column + " = NULL WHERE 1 = 0";
            case DELETE -> "DELETE FROM " + table + " WHERE 1 = 0";
        };

        assertThat(run(grantee(probe.grantee()), sql)).as(sql).isEqualTo(probe.expected());
    }

    @Test
    void theRuntimeUserMayDrawFromTheIdentitySequences() throws SQLException {
        for (String sequence : SEQUENCES) {
            assertThat(run(appUser, nextValue(users.ownerSchema() + "." + sequence)))
                    .as(sequence)
                    .isEqualTo(Outcome.ALLOWED);
        }
    }

    @Test
    void thePurgeUserMayNotDrawFromTheIdentitySequences() throws SQLException {
        for (String sequence : SEQUENCES) {
            assertThat(run(purgeUser, nextValue(users.ownerSchema() + "." + sequence)))
                    .as(sequence)
                    .isEqualTo(Outcome.DENIED);
        }
    }

    @Test
    void theRuntimeUserCannotChangeTheSchema() throws SQLException {
        String schema = users.ownerSchema();

        assertThat(run(appUser, "CREATE TABLE " + schema + ".GRANT_PROBE (ID INTEGER)"))
                .isEqualTo(Outcome.DENIED);
        assertThat(run(appUser, "DROP TABLE " + schema + ".APP_USER")).isEqualTo(Outcome.DENIED);
        assertThat(run(appUser, "ALTER TABLE " + schema + ".TEAM ADD PROBE INTEGER"))
                .isEqualTo(Outcome.DENIED);
    }

    private String grantee(String who) {
        return "app".equals(who) ? appUser : purgeUser;
    }

    /** Runs one statement as {@code user} and rolls back whatever it did. */
    private Outcome run(String user, String sql) throws SQLException {
        try (Connection connection = users.login(user)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
                return Outcome.ALLOWED;
            } catch (SQLException e) {
                if (isPrivilegeDenied(e)) {
                    return Outcome.DENIED;
                }
                if (isNotNullViolation(e)) {
                    return Outcome.ALLOWED;
                }
                throw e;
            } finally {
                connection.rollback();
            }
        }
    }
}
