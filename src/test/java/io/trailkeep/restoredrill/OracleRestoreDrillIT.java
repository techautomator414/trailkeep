// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.restoredrill;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.shared.persistence.DatabaseVendor;
import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import io.trailkeep.testsupport.TestDatabaseUsers;
import io.trailkeep.testsupport.TestDatabaseUsers.FreshOwner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * The restore drill on Oracle (Free 23 by default, {@code -Dtrailkeep.oracle.image=oracle-xe} for XE 21): Data Pump
 * {@code expdp} of the owner's schema, consistent as of one moment ({@code FLASHBACK_TIME=SYSTIMESTAMP}), a new
 * database, and {@code impdp}, which creates the owner and rebuilds the Oracle Text index from the imported rows.
 *
 * <p>The owner has exactly the privileges deploy/README.md gives {@code TRAILKEEP_OWNER}. The seed is loaded as the
 * runbook's go-live import is: the schema owner switches {@code IX_CASE_SEARCH_TEXT} to manual sync, the rows are
 * loaded, the owner synchronises the index once and restores {@code SYNC (ON COMMIT)}. {@code CTX_DDL} is granted to
 * the owner by {@code SYS} for that window only and revoked after it (the owner needs no such grant otherwise, and on
 * Free 23 {@code SYSTEM} cannot grant it). The last step names {@code OPTIMIZE (AUTO_DAILY)} again: on XE 21 a {@code
 * REPLACE METADATA SYNC} drops it from the index's metadata, so the next import would recreate the index without its
 * nightly optimisation (the drill caught that). The containers get three 512 MB redo log groups (issue 308).
 * Opt-in: the {@code perf-it} profile's {@code restore-drill-oracle} execution, which no workflow runs.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("restore-drill-oracle")
class OracleRestoreDrillIT extends AbstractRestoreDrillIT {

    static {
        if (System.getProperty(OracleTestcontainersConfiguration.REDO_PROPERTY) == null) {
            System.setProperty(OracleTestcontainersConfiguration.REDO_PROPERTY, "512");
        }
    }

    private static final String OWNER = "TKDRILL";
    private static final String DUMP_FILE = "trailkeep.dmp";
    private static final Pattern SLASH_LINE = Pattern.compile("(?m)^/[ \\t]*$");
    private static final Pattern PDB = Pattern.compile("/([A-Za-z0-9_]+)$");

    @Override
    String vendorProfile() {
        return "oracle-test";
    }

    @Override
    DatabaseVendor vendor() {
        return DatabaseVendor.ORACLE;
    }

    @Override
    String title() {
        return "Restore drill, Oracle (" + OracleTestcontainersConfiguration.imageName() + ")";
    }

    @Override
    String fileName() {
        return "restore-drill-" + OracleTestcontainersConfiguration.imageName().replaceAll("[^A-Za-z0-9]+", "-")
                + ".md";
    }

    @Override
    JdbcDatabaseContainer<?> newDatabase() {
        return OracleTestcontainersConfiguration.start(OracleTestcontainersConfiguration.imageName());
    }

    @Override
    Properties connectionProperties() {
        // The audit trail's JSON and the search text are CLOBs: fetch them with their rows, not one round trip each.
        Properties properties = new Properties();
        properties.setProperty("oracle.jdbc.defaultLobPrefetchSize", "65536");
        return properties;
    }

    @Override
    Owner newOwner(JdbcDatabaseContainer<?> database, TestDatabaseUsers users) throws SQLException {
        FreshOwner owner = users.freshOwner(OWNER);
        return new Owner(owner.username(), owner.password(), owner.schema());
    }

    @Override
    void seed(JdbcDatabaseContainer<?> database, Owner owner, Connection connection, DrillReport report)
            throws Exception {
        sysdba(database, "GRANT EXECUTE ON CTXSYS.CTX_DDL TO " + OWNER);
        try (Statement statement = connection.createStatement()) {
            report.run(
                    "Index switched to manual sync (schema owner)",
                    () -> statement.execute(
                            "ALTER INDEX IX_CASE_SEARCH_TEXT PARAMETERS ('REPLACE METADATA SYNC (MANUAL)')"));
            assertThat(single(statement, "SELECT IDX_SYNC_TYPE FROM CTX_USER_INDEXES"))
                    .isEqualTo("MANUAL");
            report.run("500k seed applied (50 000 cases, 500 000 comments)", () -> {
                for (String file : List.of("seed-cases-oracle.sql", "seed-comments-oracle.sql")) {
                    for (String sql : statements(Path.of("perf", file))) {
                        statement.execute(sql);
                    }
                }
            });
            report.fact("Rows waiting for the index after the load: "
                    + single(statement, "SELECT COUNT(*) FROM CTX_USER_PENDING"));
            report.run(
                    "CTX_DDL.SYNC_INDEX (schema owner)",
                    () -> statement.execute("BEGIN CTX_DDL.SYNC_INDEX('IX_CASE_SEARCH_TEXT'); END;"));
            assertThat(single(statement, "SELECT COUNT(*) FROM CTX_USER_PENDING"))
                    .isEqualTo("0");
            report.run(
                    "Index back to SYNC (ON COMMIT) (schema owner)",
                    () -> statement.execute("ALTER INDEX IX_CASE_SEARCH_TEXT PARAMETERS"
                            + " ('REPLACE METADATA SYNC (ON COMMIT) OPTIMIZE (AUTO_DAILY)')"));
            assertThat(single(statement, "SELECT IDX_SYNC_TYPE FROM CTX_USER_INDEXES"))
                    .isEqualTo("ON COMMIT");
        } finally {
            sysdba(database, "REVOKE EXECUTE ON CTXSYS.CTX_DDL FROM " + OWNER);
        }
    }

    @Override
    String dumpPath(JdbcDatabaseContainer<?> database) throws SQLException {
        try (Connection admin = admin(database);
                Statement statement = admin.createStatement()) {
            return single(
                            statement,
                            "SELECT DIRECTORY_PATH FROM DBA_DIRECTORIES WHERE DIRECTORY_NAME = 'DATA_PUMP_DIR'")
                    + "/" + DUMP_FILE;
        }
    }

    @Override
    void backup(JdbcDatabaseContainer<?> database, Owner owner, String path) throws Exception {
        String log = DrillDatabase.exec(
                database,
                "expdp",
                "system/" + database.getPassword() + "@//localhost:1521/" + pdb(database),
                "SCHEMAS=" + OWNER,
                "DIRECTORY=DATA_PUMP_DIR",
                "DUMPFILE=" + DUMP_FILE,
                "LOGFILE=trailkeep-expdp.log",
                "FLASHBACK_TIME=SYSTIMESTAMP",
                "REUSE_DUMPFILES=YES");
        assertThat(log).as("expdp").contains("successfully completed").doesNotContain("ORA-");
    }

    @Override
    void restore(JdbcDatabaseContainer<?> database, Owner owner, String path, DrillReport report) throws Exception {
        String log = DrillDatabase.exec(
                database,
                "impdp",
                "system/" + database.getPassword() + "@//localhost:1521/" + pdb(database),
                "SCHEMAS=" + OWNER,
                "DIRECTORY=DATA_PUMP_DIR",
                "DUMPFILE=" + DUMP_FILE,
                "LOGFILE=trailkeep-impdp.log");
        assertThat(log).as("impdp").contains("successfully completed").doesNotContain("ORA-");
    }

    @Override
    Map<String, String> indexState(JdbcDatabaseContainer<?> database, Connection owner) throws SQLException {
        Map<String, String> state = new LinkedHashMap<>();
        try (Statement statement = owner.createStatement()) {
            state.put(
                    "index",
                    single(
                            statement,
                            "SELECT STATUS || '/' || DOMIDX_STATUS || '/' || DOMIDX_OPSTATUS"
                                    + " FROM USER_INDEXES WHERE INDEX_NAME = 'IX_CASE_SEARCH_TEXT'"));
            state.put(
                    "text",
                    single(
                            statement,
                            "SELECT IDX_STATUS || ', ' || IDX_SYNC_TYPE FROM CTX_USER_INDEXES"
                                    + " WHERE IDX_NAME = 'IX_CASE_SEARCH_TEXT'"));
            state.put("pending", single(statement, "SELECT COUNT(*) FROM CTX_USER_PENDING"));
            state.put("errors", single(statement, "SELECT COUNT(*) FROM CTX_USER_INDEX_ERRORS"));
            state.put(
                    "CASE_SEARCH statistics locked",
                    single(
                            statement,
                            "SELECT NVL(STATTYPE_LOCKED, 'NO') FROM USER_TAB_STATISTICS"
                                    + " WHERE TABLE_NAME = 'CASE_SEARCH'"));
            state.put(
                    "index statistics",
                    single(
                            statement,
                            "SELECT COUNT(*) FROM USER_IND_STATISTICS"
                                    + " WHERE INDEX_NAME = 'IX_CASE_SEARCH_TEXT' AND LAST_ANALYZED IS NOT NULL"));
            state.put(
                    "kestrel",
                    single(statement, "SELECT COUNT(*) FROM CASE_SEARCH WHERE CONTAINS(SEARCH_TEXT, 'kestrel') > 0"));
        }
        try (Connection admin = admin(database);
                Statement statement = admin.createStatement()) {
            // OPTIMIZE (AUTO_DAILY): CTXSYS's two scheduler jobs for the index, which an import must recreate.
            state.put(
                    "nightly optimize jobs",
                    single(
                            statement,
                            "SELECT COUNT(*) || ' enabled ' ||"
                                    + " COUNT(CASE WHEN ENABLED = 'TRUE' THEN 1 END) FROM DBA_SCHEDULER_JOBS"
                                    + " WHERE OWNER = 'CTXSYS' AND JOB_NAME LIKE 'DR$%'"));
        }
        return state;
    }

    @Override
    long databaseBytes(Connection owner) throws SQLException {
        try (Statement statement = owner.createStatement()) {
            return Long.parseLong(single(statement, "SELECT NVL(SUM(BYTES), 0) FROM USER_SEGMENTS"));
        }
    }

    @Override
    Map<String, Long> sequences(Connection owner) throws SQLException {
        Map<String, Long> marks = new TreeMap<>();
        try (Statement statement = owner.createStatement();
                ResultSet rows = statement.executeQuery("SELECT SEQUENCE_NAME, LAST_NUMBER FROM USER_SEQUENCES")) {
            while (rows.next()) {
                marks.put(rows.getString(1), rows.getLong(2));
            }
        }
        return marks;
    }

    /** Runs one statement as {@code SYS} in the database's PDB, through SQL*Plus in the container. */
    private static void sysdba(JdbcDatabaseContainer<?> database, String sql) throws Exception {
        String script = "WHENEVER SQLERROR EXIT FAILURE\nALTER SESSION SET CONTAINER = " + pdb(database) + ";\n" + sql
                + ";\nEXIT\n";
        String output = DrillDatabase.exec(database, "bash", "-c", "sqlplus -s / as sysdba <<'EOF'\n" + script + "EOF");
        assertThat(output).as(sql).doesNotContain("ORA-");
    }

    private static String pdb(JdbcDatabaseContainer<?> database) {
        Matcher matcher = PDB.matcher(database.getJdbcUrl());
        if (!matcher.find()) {
            throw new IllegalStateException("No PDB in " + database.getJdbcUrl());
        }
        return matcher.group(1);
    }

    private static String single(Statement statement, String sql) throws SQLException {
        try (ResultSet row = statement.executeQuery(sql)) {
            return row.next() ? String.valueOf(row.getString(1)) : "none";
        }
    }

    /** The SQL*Plus seed's statements: each ends with a line holding only "/"; comment lines dropped. */
    private static List<String> statements(Path file) {
        try {
            return SLASH_LINE
                    .splitAsStream(Files.readString(file))
                    .map(statement -> statement
                            .lines()
                            .filter(line -> !line.startsWith("--"))
                            .collect(Collectors.joining("\n"))
                            .strip())
                    .filter(sql -> !sql.isEmpty())
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
