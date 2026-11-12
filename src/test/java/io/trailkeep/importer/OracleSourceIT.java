// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.importer.SourceQueryJdbcDao.Session;
import io.trailkeep.importer.SourceQueryJdbcDao.Source;
import io.trailkeep.importer.SourceQueryValidator.ValidatedSourceQuery;
import io.trailkeep.importer.SourceQueryValidator.Vendor;
import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;

/**
 * The Oracle source (WP-26 part o, design §4.1, §4.3, §11; {@code -Poracle-it}, both images): the grants recipe of
 * docs/importer/source-accounts.md applied to a synthetic legacy schema in the test container, {@link
 * SourceQueryJdbcDao} reading as that account (typed values, LOBs, one read-only snapshot, the SCN fingerprint), what
 * the recipe achieves, and {@code extract} end to end with a {@code BLOB} content query. The legacy users are created
 * as {@code SYSTEM} before each test and dropped after it, so the shared test database is left as it was found.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleSourceIT extends ExtractJobSupport {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final byte[] SCAN = "synthetic scan from an Oracle BLOB".getBytes(StandardCharsets.UTF_8);
    private static final String PASSWORD = "Tk" + HexFormat.of().formatHex(new SecureRandom().generateSeed(12));

    @Autowired
    private JdbcConnectionDetails connection;

    private final SourceQueryJdbcDao dao = new SourceQueryJdbcDao();

    @Override
    String vendorProfile() {
        return "oracle-test";
    }

    @BeforeEach
    void legacySchemaWithTheRecipeApplied() throws SQLException {
        dropLegacyUsers();
        admin(
                "CREATE USER tk_legacy IDENTIFIED BY \"" + PASSWORD + "\" QUOTA UNLIMITED ON USERS",
                "CREATE TABLE tk_legacy.people (user_id VARCHAR2(10), name VARCHAR2(50), mail VARCHAR2(50))",
                "INSERT INTO tk_legacy.people VALUES ('u1', 'Synthetic One', 'one@example.test')",
                "INSERT INTO tk_legacy.people VALUES ('u2', 'Synthetic Two', 'two@example.test')",
                "CREATE TABLE tk_legacy.cases (case_id VARCHAR2(10), subject VARCHAR2(50), details CLOB,"
                        + " state VARCHAR2(10), opened DATE, found_on DATE, owner VARCHAR2(10))",
                "CREATE TABLE tk_legacy.notes (note_id VARCHAR2(10), case_id VARCHAR2(10), author VARCHAR2(10),"
                        + " created TIMESTAMP, body CLOB)",
                "CREATE TABLE tk_legacy.files (file_id VARCHAR2(10), case_id VARCHAR2(10), name VARCHAR2(50),"
                        + " bytes NUMBER(12), content BLOB)",
                "CREATE TABLE tk_legacy.typed (id NUMBER(10), opened DATE, at_ts TIMESTAMP,"
                        + " zoned TIMESTAMP WITH TIME ZONE, local_ts TIMESTAMP WITH LOCAL TIME ZONE,"
                        + " amount NUMBER(10,2), body CLOB, content BLOB)",
                "INSERT INTO tk_legacy.typed VALUES (100, TIMESTAMP '2019-03-04 09:00:00',"
                        + " TIMESTAMP '2019-03-04 09:00:00.25', TIMESTAMP '2019-06-01 09:30:00 +02:00',"
                        + " TIMESTAMP '2019-06-01 09:30:00 +00:00', 12.50, TO_CLOB('Synthetic long text'),"
                        + " HEXTORAW('CAFE'))",
                "CREATE TABLE tk_legacy.secrets (id NUMBER(10))",
                // The recipe, as docs/importer/source-accounts.md gives it.
                "CREATE USER tk_source IDENTIFIED BY \"" + PASSWORD + "\"",
                "GRANT CREATE SESSION TO tk_source",
                "GRANT READ ON tk_legacy.people TO tk_source",
                "GRANT READ ON tk_legacy.cases TO tk_source",
                "GRANT READ ON tk_legacy.notes TO tk_source",
                "GRANT READ ON tk_legacy.files TO tk_source",
                "GRANT READ ON tk_legacy.typed TO tk_source");
        for (String[] legacyCase : new String[][] {
            {"c1", "Synthetic case one", "Open"},
            {"c2", "Synthetic case c2", "Closed"},
            {"c3", "Synthetic case c3", "Weird"}
        }) {
            admin("INSERT INTO tk_legacy.cases VALUES ('" + legacyCase[0] + "', '" + legacyCase[1]
                    + "', TO_CLOB('Synthetic details'), '" + legacyCase[2]
                    + "', TIMESTAMP '2019-03-01 09:30:00', DATE '2019-02-28', 'u1')");
        }
        for (String note : List.of("n1", "n2", "n2")) {
            admin("INSERT INTO tk_legacy.notes VALUES ('" + note + "', 'c1', 'u2',"
                    + " TIMESTAMP '2019-03-02 10:00:00', TO_CLOB('Synthetic note'))");
        }
        try (Connection admin = systemLogin();
                PreparedStatement file = admin.prepareStatement(
                        "INSERT INTO tk_legacy.files VALUES ('f1', 'c1', 'scan.pdf', 2048, ?)")) {
            file.setBytes(1, SCAN);
            file.executeUpdate();
        }
        awaitReadOnlyReads();
    }

    /**
     * A read-only transaction cannot read a table created moments before it (ORA-01466: the dictionary records the
     * creation at a coarser grain than the transaction's read point), which only a test that creates its legacy schema
     * just before reading it meets. Waits, at most 30 s, until one can.
     */
    private void awaitReadOnlyReads() throws SQLException {
        for (int attempt = 0; ; attempt++) {
            try (Connection account = DriverManager.getConnection(connection.getJdbcUrl(), "tk_source", PASSWORD);
                    Statement statement = account.createStatement()) {
                account.setAutoCommit(false);
                statement.execute("SET TRANSACTION READ ONLY");
                for (String table : List.of("people", "cases", "notes", "files", "typed")) {
                    // Test-only probe over the fixed names of this list.
                    statement
                            .executeQuery("SELECT COUNT(*) FROM tk_legacy." + table)
                            .close();
                }
                account.rollback();
                return;
            } catch (SQLException e) {
                if (e.getErrorCode() != 1466 || attempt >= 60) {
                    throw e;
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
        }
    }

    @AfterEach
    void dropLegacyUsers() throws SQLException {
        for (String user : List.of("tk_source", "tk_legacy")) {
            try (Connection admin = systemLogin();
                    Statement statement = admin.createStatement()) {
                // Test-only DDL over the fixed names of this list.
                statement.execute("DROP USER " + user + " CASCADE");
            } catch (SQLException missing) {
                // ORA-01918: not created yet.
            }
        }
    }

    @Test
    void valuesArriveTypedInOneReadOnlySnapshotWithAnScnFingerprint() throws SQLException {
        List<Map<String, @Nullable Object>> rows = new ArrayList<>();
        String first;
        try (Session session = dao.open(source())) {
            dao.stream(session, query("SELECT * FROM tk_legacy.typed WHERE id = ?"), List.of(100), TIMEOUT, rows::add);
            admin("INSERT INTO tk_legacy.typed (id) VALUES (101)");
            assertThat(dao.testFetch(session, query("SELECT id FROM tk_legacy.typed"), TIMEOUT))
                    .isOne();
            List<Map<String, @Nullable Object>> later = new ArrayList<>();
            dao.stream(session, query("SELECT id FROM tk_legacy.typed"), List.of(), TIMEOUT, later::add);
            assertThat(later)
                    .as("a row committed after the snapshot is not seen")
                    .hasSize(1);
            first = dao.fingerprint(session);
        }
        try (Session session = dao.open(source())) {
            assertThat(dao.fingerprint(session))
                    .as("a new session is a new read point")
                    .matches("oracle:scn=[0-9]+:[0-9a-f]{64}")
                    .isNotEqualTo(first);
        }

        Map<String, @Nullable Object> row = rows.getFirst();
        assertThat(row.get("opened")).as("DATE carries a time").isEqualTo(LocalDateTime.of(2019, 3, 4, 9, 0));
        assertThat(row.get("at_ts")).isEqualTo(LocalDateTime.of(2019, 3, 4, 9, 0, 0, 250_000_000));
        assertThat(Objects.requireNonNull((OffsetDateTime) row.get("zoned")).toInstant())
                .isEqualTo(OffsetDateTime.parse("2019-06-01T07:30:00Z").toInstant());
        assertThat(row.get("local_ts")).isEqualTo(OffsetDateTime.parse("2019-06-01T09:30:00Z"));
        assertThat((BigDecimal) row.get("amount")).as("NUMBER keeps no scale").isEqualByComparingTo("12.50");
        assertThat(row.get("body")).isEqualTo("Synthetic long text");
        assertThat((byte[]) row.get("content")).containsExactly(0xCA, 0xFE);
    }

    /** What the recipe achieves, as the source account over plain JDBC, and the validator's part. */
    @Test
    void theAccountCannotWriteLockOrReadAnUngrantedTable() throws SQLException {
        try (Connection account = DriverManager.getConnection(connection.getJdbcUrl(), "tk_source", PASSWORD);
                Statement statement = account.createStatement()) {
            for (String refused : List.of(
                    "INSERT INTO tk_legacy.cases (case_id) VALUES ('x')",
                    "UPDATE tk_legacy.cases SET state = 'x'",
                    "DELETE FROM tk_legacy.cases",
                    "SELECT case_id FROM tk_legacy.cases FOR UPDATE",
                    "LOCK TABLE tk_legacy.cases IN EXCLUSIVE MODE",
                    "CREATE TABLE tk_owned (id NUMBER)")) {
                assertThatThrownBy(() -> statement.execute(refused))
                        .as(refused)
                        .isInstanceOfSatisfying(
                                SQLException.class,
                                e -> assertThat(e.getErrorCode()).isIn(1031, 41900));
            }
        }
        try (Session session = dao.open(source())) {
            assertThatThrownBy(() -> dao.testFetch(session, query("SELECT id FROM tk_legacy.secrets"), TIMEOUT))
                    .isInstanceOfSatisfying(SourceQueryException.class, e -> {
                        assertThat(e.code()).isEqualTo("SOURCE_PERMISSION_DENIED");
                        assertThat(e.getMessage()).doesNotContain("secrets");
                    });
            assertThat(dao.testFetch(session, query("SELECT id FROM tk_legacy.typed"), TIMEOUT))
                    .as("a failed query does not end the read-only transaction")
                    .isOne();
        }
        for (String call : List.of(
                "SELECT DBMS_RANDOM.VALUE FROM DUAL", "SELECT tk_legacy.leak() FROM DUAL", "SELECT id FROM t@remote")) {
            assertThat(SourceQueryValidator.validate(call, Vendor.ORACLE).problems())
                    .as(call)
                    .isNotEmpty();
        }
    }

    @Test
    void validateMappingTestFetchesAndExtractStagesWithTheBlobInTheContentArea(@TempDir Path directory)
            throws Exception {
        String text = LegacyPostgresSource.mapping(MAPPING)
                .replace("vendor: postgresql", "vendor: oracle")
                .replace("legacy.", "tk_legacy.");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("import.source.url", connection.getJdbcUrl())
                .withProperty("import.source.user", "tk_source")
                .withProperty("import.source.password", PASSWORD);
        assertThat(new SourceTestFetch(dao, environment).check(text.getBytes(StandardCharsets.UTF_8)))
                .isEmpty();

        Path mapping = mapping(directory, text);
        Path area = Files.createDirectories(directory.resolve("content"));
        long run = validatedRun(mapping);
        extract(
                        run,
                        mapping,
                        null,
                        "--import.source.url=" + connection.getJdbcUrl(),
                        "--import.source.user=tk_source",
                        "--import.source.password=" + PASSWORD,
                        "--import.content-area=" + area)
                .close();

        assertThat(staged(run)).containsExactly(2, 3, 2, 1, 0);
        assertThat(issues(run))
                .containsExactlyInAnyOrder(
                        List.of("CASE", "ERROR", "LOOKUP_MISSING", hash("c3")),
                        List.of("COMMENT", "ERROR", "DUPLICATE_KEY", hash("n2")));
        assertThat(jdbc.queryForObject("SELECT SOURCE_FINGERPRINT FROM IMPORT_RUN WHERE ID = ?", String.class, run))
                .startsWith("oracle:scn=");
        String sha256 = ValidateMappingCommand.sha256(SCAN);
        assertThat(jdbc.queryForObject(
                        "SELECT CONTENT_POINTER FROM IMPORT_STG_ATTACHMENT WHERE RUN_ID = ?", String.class, run))
                .isEqualTo(sha256);
        assertThat(area.resolve(Long.toString(run)).resolve(sha256)).hasBinaryContent(SCAN);
    }

    private Source source() {
        return new Source(connection.getJdbcUrl(), "tk_source", PASSWORD, Vendor.ORACLE);
    }

    private static ValidatedSourceQuery query(String sql) {
        SourceQueryValidator.Result result = SourceQueryValidator.validate(sql, Vendor.ORACLE);
        return Objects.requireNonNull(result.query(), () -> "refused: " + result.problems());
    }

    private Connection systemLogin() throws SQLException {
        return DriverManager.getConnection(connection.getJdbcUrl(), "system", connection.getPassword());
    }

    private void admin(String... statements) throws SQLException {
        try (Connection admin = systemLogin();
                Statement statement = admin.createStatement()) {
            for (String sql : statements) {
                // Test-only DDL and seed rows, fixed in this class.
                statement.execute(sql);
            }
        }
    }
}
