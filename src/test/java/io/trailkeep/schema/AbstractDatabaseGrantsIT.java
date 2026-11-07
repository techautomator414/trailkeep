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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
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

    /**
     * Identity (V1), audit (V2), admin (V3), case (V4), comment (V4.1), pin (V4.2), attachment (V4.3), saved view
     * (V4.7), outbox (V4.8), alert (V4.9), update request (V4.10), closure (V5), reopen (V5.1) and SLA job (V6)
     * tables, and the column each probe touches: the first key column.
     */
    static final Map<String, String> TABLES = Map.ofEntries(
            Map.entry("APP_USER", "ID"),
            Map.entry("TEAM", "ID"),
            Map.entry("APP_USER_TEAM", "USER_ID"),
            Map.entry("APP_USER_GLOBAL_ROLE", "USER_ID"),
            Map.entry("AUDIT_EVENT", "ID"),
            Map.entry("SLA_POLICY", "ID"),
            Map.entry("CASE_TYPE", "ID"),
            Map.entry("CASE_TYPE_OUTCOME", "CASE_TYPE_ID"),
            Map.entry("CASE_RECORD", "ID"),
            Map.entry("CASE_SLA", "CASE_ID"),
            Map.entry("CASE_SEARCH", "CASE_ID"),
            Map.entry("CASE_COMMENT", "ID"),
            Map.entry("CASE_COMMENT_PIN", "COMMENT_ID"),
            Map.entry("CASE_ATTACHMENT", "ID"),
            Map.entry("CASE_ATTACHMENT_CHUNK", "ATTACHMENT_ID"),
            Map.entry("SPRING_SESSION", "PRIMARY_ID"),
            Map.entry("SPRING_SESSION_ATTRIBUTES", "SESSION_PRIMARY_ID"),
            Map.entry("OIDC_SESSION", "SESSION_ID"),
            Map.entry("SAVED_VIEW", "ID"),
            Map.entry("NOTIFICATION_OUTBOX", "ID"),
            Map.entry("USER_ALERT", "ID"),
            Map.entry("UPDATE_REQUEST", "ID"),
            Map.entry("CLOSURE_REQUEST", "ID"),
            Map.entry("CASE_REOPEN", "ID"),
            Map.entry("ALERT_LEDGER", "ID"),
            Map.entry("JOB_RUN", "JOB_NAME"),
            Map.entry("SHEDLOCK", "NAME"));

    /** What the runtime user may do; anything absent is denied. */
    static final Map<String, Set<Privilege>> APP_USER_GRANTS = Map.ofEntries(
            Map.entry("APP_USER", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            Map.entry("TEAM", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            Map.entry("APP_USER_TEAM", EnumSet.allOf(Privilege.class)),
            Map.entry("APP_USER_GLOBAL_ROLE", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.DELETE)),
            Map.entry("AUDIT_EVENT", EnumSet.of(Privilege.SELECT, Privilege.INSERT)),
            Map.entry("SLA_POLICY", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            Map.entry("CASE_TYPE", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            Map.entry("CASE_TYPE_OUTCOME", EnumSet.allOf(Privilege.class)),
            Map.entry("CASE_RECORD", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            Map.entry("CASE_SLA", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            Map.entry("CASE_SEARCH", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            Map.entry("CASE_COMMENT", EnumSet.of(Privilege.SELECT, Privilege.INSERT)),
            Map.entry("CASE_COMMENT_PIN", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.DELETE)),
            // UPDATE of the release columns only; see theRuntimeUserMayChangeAnAttachmentOnlyByReleasingIt.
            Map.entry("CASE_ATTACHMENT", EnumSet.of(Privilege.SELECT, Privilege.INSERT)),
            Map.entry("CASE_ATTACHMENT_CHUNK", EnumSet.of(Privilege.SELECT, Privilege.INSERT)),
            Map.entry("SPRING_SESSION", EnumSet.allOf(Privilege.class)),
            Map.entry("SPRING_SESSION_ATTRIBUTES", EnumSet.allOf(Privilege.class)),
            Map.entry("OIDC_SESSION", EnumSet.allOf(Privilege.class)),
            // A person deletes their own views; a view is never changed, so no UPDATE.
            Map.entry("SAVED_VIEW", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.DELETE)),
            // Mail is sent and its status changed, never deleted by the app: trimming sent rows is the purge user's.
            Map.entry("NOTIFICATION_OUTBOX", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            // A person marks their alerts read and deletes them (BUILD_PLAN §8 rule 7 allows DELETE here).
            Map.entry("USER_ALERT", EnumSet.allOf(Privilege.class)),
            // A request is answered, never deleted: it is case history.
            Map.entry("UPDATE_REQUEST", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            // A closure request is decided, never deleted: it is case history.
            Map.entry("CLOSURE_REQUEST", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            // A reopen is history: written once, never changed or deleted by the app.
            Map.entry("CASE_REOPEN", EnumSet.of(Privilege.SELECT, Privilege.INSERT)),
            // An alert once raised is history: never changed or deleted by the app (BUILD_PLAN §8 rule 7).
            Map.entry("ALERT_LEDGER", EnumSet.of(Privilege.SELECT, Privilege.INSERT)),
            // Each job's last success, replaced on every successful run.
            Map.entry("JOB_RUN", EnumSet.of(Privilege.SELECT, Privilege.INSERT, Privilege.UPDATE)),
            // ShedLock inserts, updates and may delete its lock rows (BUILD_PLAN §8 rule 7 allows DELETE here).
            Map.entry("SHEDLOCK", EnumSet.allOf(Privilege.class)));

    /** What the purge user may do on these tables: trim the outbox (V4.8); its case-data grants arrive with WP-24. */
    static final Map<String, Set<Privilege>> PURGE_USER_GRANTS =
            Map.of("NOTIFICATION_OUTBOX", EnumSet.of(Privilege.SELECT, Privilege.DELETE));

    static final List<String> SEQUENCES = List.of(
            "SEQ_APP_USER",
            "SEQ_TEAM",
            "SEQ_AUDIT_EVENT",
            "SEQ_SLA_POLICY",
            "SEQ_CASE_TYPE",
            "SEQ_CASE_RECORD",
            "SEQ_CASE_NUMBER",
            "SEQ_CASE_COMMENT",
            "SEQ_CASE_ATTACHMENT",
            "SEQ_SAVED_VIEW",
            "SEQ_NOTIFICATION_OUTBOX",
            "SEQ_USER_ALERT",
            "SEQ_UPDATE_REQUEST",
            "SEQ_CLOSURE_REQUEST",
            "SEQ_CASE_REOPEN",
            "SEQ_ALERT_LEDGER");

    /**
     * One open connection per grantee for the whole class. Opening a connection per probe (about 100 in a burst, each
     * also taking an administrator connection to reset the password) exhausts the server processes or listener
     * handlers of Oracle Free 23 (ORA-12516). Every probe rolls back, so sharing a connection leaks no state.
     */
    private static final Map<String, Connection> CONNECTIONS = new HashMap<>();

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

    /**
     * The INSERT-only negative test of WP-13, beside the matrix: comments are evidence, so no statement of the
     * runtime user may change or remove one, however it is phrased. Each statement would touch every row, and each
     * is refused by the privilege check before any row is read.
     */
    @Test
    void theRuntimeUserCanNeitherChangeNorRemoveAComment() throws SQLException {
        String comments = users.ownerSchema() + ".CASE_COMMENT";

        assertThat(run(appUser, "UPDATE " + comments + " SET BODY = 'rewritten'"))
                .isEqualTo(Outcome.DENIED);
        assertThat(run(appUser, "UPDATE " + comments + " SET AUTHOR_NAME_SNAPSHOT = 'someone else'"))
                .isEqualTo(Outcome.DENIED);
        assertThat(run(appUser, "DELETE FROM " + comments)).isEqualTo(Outcome.DENIED);
        assertThat(run(appUser, "TRUNCATE TABLE " + comments)).isEqualTo(Outcome.DENIED);
        assertThat(run(appUser, "SELECT COUNT(*) FROM " + comments)).isEqualTo(Outcome.ALLOWED);
    }

    /**
     * Attachments are evidence too (WP-14): the runtime user may write only the release of a quarantined file, never
     * its name, type, size, hash, detection, uploader or content, and may delete neither the row nor its chunks.
     */
    @Test
    void theRuntimeUserMayChangeAnAttachmentOnlyByReleasingIt() throws SQLException {
        String attachments = users.ownerSchema() + ".CASE_ATTACHMENT";
        String chunks = users.ownerSchema() + ".CASE_ATTACHMENT_CHUNK";

        for (String column : List.of("SCAN_STATUS", "RELEASED_BY", "RELEASED_AT", "RELEASED_REASON")) {
            assertThat(run(appUser, "UPDATE " + attachments + " SET " + column + " = NULL WHERE 1 = 0"))
                    .as(column)
                    .isEqualTo(Outcome.ALLOWED);
        }
        for (String column : List.of(
                "ID", "CASE_ID", "FILE_NAME", "CONTENT_TYPE", "SIZE_BYTES", "SHA256", "SCAN_DETAIL", "UPLOADED_BY")) {
            assertThat(run(appUser, "UPDATE " + attachments + " SET " + column + " = NULL"))
                    .as(column)
                    .isEqualTo(Outcome.DENIED);
        }
        assertThat(run(appUser, "UPDATE " + chunks + " SET CHUNK = NULL")).isEqualTo(Outcome.DENIED);
        // A release locks the row first (WP-14 part 4), which PostgreSQL allows only with an UPDATE grant.
        assertThat(run(appUser, "SELECT ID FROM " + attachments + " WHERE 1 = 0 FOR UPDATE"))
                .isEqualTo(Outcome.ALLOWED);
        assertThat(run(appUser, "DELETE FROM " + attachments)).isEqualTo(Outcome.DENIED);
        assertThat(run(appUser, "DELETE FROM " + chunks)).isEqualTo(Outcome.DENIED);
        assertThat(run(purgeUser, "UPDATE " + attachments + " SET RELEASED_REASON = NULL WHERE 1 = 0"))
                .isEqualTo(Outcome.DENIED);
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

    /** Closes the shared grantee connections once every probe of the class has run. */
    @AfterAll
    static void closeConnections() throws SQLException {
        SQLException failure = null;
        for (Connection connection : CONNECTIONS.values()) {
            try {
                connection.close();
            } catch (SQLException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        CONNECTIONS.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private Connection connectionFor(String user) throws SQLException {
        Connection connection = CONNECTIONS.get(user);
        if (connection == null || connection.isClosed()) {
            connection = users.login(user);
            connection.setAutoCommit(false);
            CONNECTIONS.put(user, connection);
        }
        return connection;
    }

    /** Runs one statement as {@code user} on that user's shared connection and rolls back whatever it did. */
    private Outcome run(String user, String sql) throws SQLException {
        Connection connection = connectionFor(user);
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
