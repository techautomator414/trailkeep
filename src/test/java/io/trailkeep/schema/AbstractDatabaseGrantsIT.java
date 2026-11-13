// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema;

import static io.trailkeep.schema.GrantsMatrix.APP_USER_GRANTS;
import static io.trailkeep.schema.GrantsMatrix.IMPORTER_SEQUENCES;
import static io.trailkeep.schema.GrantsMatrix.IMPORT_USER_GRANTS;
import static io.trailkeep.schema.GrantsMatrix.LOAD_SEQUENCES;
import static io.trailkeep.schema.GrantsMatrix.PURGE_USER_GRANTS;
import static io.trailkeep.schema.GrantsMatrix.SEQUENCES;
import static io.trailkeep.schema.GrantsMatrix.TABLES;
import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.schema.GrantsMatrix.Privilege;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.TestDatabaseUsers;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
 * Proves the vendor {@code R__grants.sql} matrix ({@link GrantsMatrix}) by logging in as the runtime user, the purge
 * user and the import user (created by the test-only {@link TestDatabaseUsers}) and executing one statement per
 * table, privilege and user. A denied statement must fail with the vendor's privilege error; an allowed one must get
 * past the privilege check. Every probe is a no-op on data: {@code WHERE 1 = 0}, or an INSERT of NULL into a NOT
 * NULL key column, which only a granted user can reach (the privilege check precedes the constraint check on both
 * vendors).
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractDatabaseGrantsIT {

    /** The outcome of one probe. */
    enum Outcome {
        ALLOWED,
        DENIED
    }

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

    @Value("${app.db.import-user}")
    private String importUser = "";

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
                probes.add(probe("import", table, privilege, IMPORT_USER_GRANTS));
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

    /**
     * Whether the privilege that lets Hibernate validate see a sequence also draws from it: Oracle's only sequence
     * privilege, SELECT, is NEXTVAL; PostgreSQL's SELECT is not (USAGE is).
     *
     * @return true on Oracle
     */
    abstract boolean seeingASequenceDrawsFromIt();

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
    void theRuntimeUserMayDrawFromTheIdentitySequencesAndOnlySeeTheImporterOnes() throws SQLException {
        for (String sequence : SEQUENCES) {
            assertThat(draw(appUser, sequence)).as(sequence).isEqualTo(Outcome.ALLOWED);
        }
        for (String sequence : IMPORTER_SEQUENCES) {
            assertThat(draw(appUser, sequence)).as(sequence).isEqualTo(drawnBySeeing());
        }
    }

    @Test
    void thePurgeUserMayDrawOnlyFromTheAuditSequence() throws SQLException {
        for (String sequence :
                Stream.concat(SEQUENCES.stream(), IMPORTER_SEQUENCES.stream()).toList()) {
            assertThat(draw(purgeUser, sequence))
                    .as(sequence)
                    .isEqualTo("SEQ_AUDIT_EVENT".equals(sequence) ? Outcome.ALLOWED : Outcome.DENIED);
        }
    }

    /**
     * The import user draws from the importer's sequences and from those of the rows the load writes (WP-26 part t),
     * and may see every other one an entity maps, for validate.
     */
    @Test
    void theImportUserMayDrawFromTheImporterSequencesAndOnlySeeTheOthers() throws SQLException {
        for (String sequence :
                Stream.concat(SEQUENCES.stream(), IMPORTER_SEQUENCES.stream()).toList()) {
            Outcome expected = sequence.startsWith("SEQ_IMPORT_")
                            || IMPORTER_SEQUENCES.contains(sequence)
                            || LOAD_SEQUENCES.contains(sequence)
                    ? Outcome.ALLOWED
                    : drawnBySeeing();
            assertThat(draw(importUser, sequence)).as(sequence).isEqualTo(expected);
        }
    }

    /**
     * An import run has two writers, each confined to its own columns: the Import runs screen signs it off, the
     * importer Job moves it on. Neither can write the other's columns or the run's identity.
     */
    @Test
    void anImportRunIsSignedOffOnlyByTheRuntimeUserAndMovedOnOnlyByTheImportUser() throws SQLException {
        String runs = users.ownerSchema() + ".IMPORT_RUN";
        List<String> signOff = List.of("APPROVED_BY", "APPROVED_AT", "ACKNOWLEDGED_WARNINGS");
        List<String> progress = List.of(
                "STATUS",
                "MAPPING_HASH",
                "SOURCE_FINGERPRINT",
                "SCAN_MODE",
                "ROWS_STAGED",
                "ROWS_LOADED",
                "STAGING_EXPIRES_AT",
                "STAGING_PURGED_AT");
        for (String column : signOff) {
            assertThat(update(appUser, runs, column)).as("app " + column).isEqualTo(Outcome.ALLOWED);
            assertThat(update(importUser, runs, column)).as("import " + column).isEqualTo(Outcome.DENIED);
        }
        for (String column : progress) {
            assertThat(update(appUser, runs, column)).as("app " + column).isEqualTo(Outcome.DENIED);
            assertThat(update(importUser, runs, column)).as("import " + column).isEqualTo(Outcome.ALLOWED);
        }
        for (String column : List.of("UPDATED_AT", "VERSION")) {
            assertThat(update(appUser, runs, column)).as("app " + column).isEqualTo(Outcome.ALLOWED);
            assertThat(update(importUser, runs, column)).as("import " + column).isEqualTo(Outcome.ALLOWED);
        }
        for (String column : List.of("KIND", "SOURCE_ID", "REQUESTED_BY", "REQUESTED_AT")) {
            assertThat(update(appUser, runs, column)).as("app " + column).isEqualTo(Outcome.DENIED);
            assertThat(update(importUser, runs, column)).as("import " + column).isEqualTo(Outcome.DENIED);
        }
    }

    /**
     * The load appends each comment it writes to its case's search text (WP-26 part u, {@code CaseSearchJdbcDao}): the
     * import user may update those two columns of a search row and nothing else, so it cannot move a row to another
     * case.
     */
    @Test
    void theImportUserMayUpdateOnlyTheSearchTextOfASearchRow() throws SQLException {
        String search = users.ownerSchema() + ".CASE_SEARCH";
        for (String column : List.of("SEARCH_TEXT", "UPDATED_AT")) {
            assertThat(update(importUser, search, column)).as(column).isEqualTo(Outcome.ALLOWED);
        }
        assertThat(update(importUser, search, "CASE_ID")).isEqualTo(Outcome.DENIED);
        assertThat(run(importUser, "UPDATE " + search + " SET SEARCH_TEXT = SEARCH_TEXT || 'x' WHERE CASE_ID = 0"))
                .as("the append statement itself")
                .isEqualTo(Outcome.ALLOWED);
    }

    /**
     * The INSERT-only negative test of WP-13, beside the matrix: comments are evidence, so no statement of the
     * runtime user may change or remove one, however it is phrased. Each statement would touch every row, and each
     * is refused by the privilege check before any row is read.
     */
    @Test
    void neitherTheRuntimeNorTheImportUserCanChangeOrRemoveAComment() throws SQLException {
        String comments = users.ownerSchema() + ".CASE_COMMENT";

        for (String user : List.of(appUser, importUser)) {
            assertThat(run(user, "UPDATE " + comments + " SET BODY = 'rewritten'"))
                    .isEqualTo(Outcome.DENIED);
            assertThat(run(user, "UPDATE " + comments + " SET AUTHOR_NAME_SNAPSHOT = 'someone else'"))
                    .isEqualTo(Outcome.DENIED);
            assertThat(run(user, "DELETE FROM " + comments)).isEqualTo(Outcome.DENIED);
            assertThat(run(user, "TRUNCATE TABLE " + comments)).isEqualTo(Outcome.DENIED);
            assertThat(run(user, "SELECT COUNT(*) FROM " + comments)).isEqualTo(Outcome.ALLOWED);
        }
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

    /**
     * {@code relink-owners} (WP-26 part z2) hands a case to its legacy owner: the import user may change a case's owner
     * and its change stamp, and nothing else of it, so it cannot move, retitle, close or tombstone a case.
     */
    @Test
    void theImportUserMayChangeOnlyTheOwnerOfACase() throws SQLException {
        String cases = users.ownerSchema() + ".CASE_RECORD";
        for (String column : List.of("OWNER_ID", "UPDATED_AT", "UPDATED_BY", "VERSION")) {
            assertThat(update(importUser, cases, column)).as(column).isEqualTo(Outcome.ALLOWED);
        }
        for (String column : List.of(
                "TEAM_ID", "TITLE", "STATUS", "SENSITIVITY", "DESCRIPTION", "LEGAL_HOLD", "PURGED_AT", "CREATED_BY")) {
            assertThat(update(importUser, cases, column)).as(column).isEqualTo(Outcome.DENIED);
        }
    }

    /** A tombstone (WP-24 part c): four columns and the row lock, nothing else; the matrix covers DELETE. */
    @Test
    void thePurgeUserMayChangeACaseOnlyIntoATombstone() throws SQLException {
        String cases = users.ownerSchema() + ".CASE_RECORD";
        List<String> columns = List.of(
                "DESCRIPTION", "SUBJECT_TEXT", "PURGED_AT", "VERSION", "TITLE", "STATUS", "LEGAL_HOLD", "PURGE_AFTER");
        for (int i = 0; i < columns.size(); i++) {
            assertThat(run(purgeUser, "UPDATE " + cases + " SET " + columns.get(i) + " = NULL WHERE 1 = 0"))
                    .as(columns.get(i))
                    .isEqualTo(i < 4 ? Outcome.ALLOWED : Outcome.DENIED);
        }
        assertThat(run(purgeUser, "SELECT ID FROM " + cases + " WHERE 1 = 0 FOR UPDATE"))
                .isEqualTo(Outcome.ALLOWED);
    }

    @Test
    void neitherTheRuntimeUserNorTheImportUserCanChangeTheSchema() throws SQLException {
        String schema = users.ownerSchema();

        for (String user : List.of(appUser, importUser)) {
            assertThat(run(user, "CREATE TABLE " + schema + ".GRANT_PROBE (ID INTEGER)"))
                    .as(user)
                    .isEqualTo(Outcome.DENIED);
            assertThat(run(user, "DROP TABLE " + schema + ".APP_USER")).as(user).isEqualTo(Outcome.DENIED);
            assertThat(run(user, "ALTER TABLE " + schema + ".TEAM ADD PROBE INTEGER"))
                    .as(user)
                    .isEqualTo(Outcome.DENIED);
        }
    }

    private String grantee(String who) {
        return switch (who) {
            case "app" -> appUser;
            case "purge" -> purgeUser;
            default -> importUser;
        };
    }

    private Outcome drawnBySeeing() {
        return seeingASequenceDrawsFromIt() ? Outcome.ALLOWED : Outcome.DENIED;
    }

    private Outcome draw(String user, String sequence) throws SQLException {
        return run(user, nextValue(users.ownerSchema() + "." + sequence));
    }

    private Outcome update(String user, String table, String column) throws SQLException {
        return run(user, "UPDATE " + table + " SET " + column + " = NULL WHERE 1 = 0");
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
