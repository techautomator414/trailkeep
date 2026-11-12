// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.importer.SourceQueryJdbcDao.Session;
import io.trailkeep.importer.SourceQueryJdbcDao.Source;
import io.trailkeep.importer.SourceQueryValidator.ValidatedSourceQuery;
import io.trailkeep.importer.SourceQueryValidator.Vendor;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
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
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The SQL Server source (WP-26 part p, design §4.1, §4.3, §11; {@code -Psqlserver-it}): the grants recipe of
 * docs/importer/source-accounts.md applied to a synthetic legacy database in a SQL Server Developer edition container
 * (its licence accepted through the image's documented variable, for testing only), {@link SourceQueryJdbcDao} reading
 * as that account (typed values, {@code VARBINARY} and {@code IMAGE}, one {@code SNAPSHOT} transaction that takes no
 * locks), what the recipe achieves, and {@code extract} end to end with an {@code IMAGE} content query. The legacy
 * database and login are created before each test and dropped after it; the container stops with the JVM.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("sqlserver")
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
class SqlServerSourceIT extends ExtractJobSupport {

    private static final DockerImageName IMAGE =
            DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-CU27-ubuntu-22.04");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final byte[] SCAN = "synthetic scan from a SQL Server IMAGE".getBytes(StandardCharsets.UTF_8);
    private static final String PASSWORD = "Tk" + HexFormat.of().formatHex(new SecureRandom().generateSeed(12));
    private static final MSSQLServerContainer LEGACY =
            new MSSQLServerContainer(IMAGE).acceptLicense().withPassword(PASSWORD);

    private final SourceQueryJdbcDao dao = new SourceQueryJdbcDao();

    @Override
    String vendorProfile() {
        return "test";
    }

    @BeforeEach
    void legacyDatabaseWithTheRecipeApplied() throws SQLException {
        synchronized (SqlServerSourceIT.class) {
            if (!LEGACY.isRunning()) {
                LEGACY.start();
            }
        }
        dropLegacyDatabase();
        admin(
                "master",
                "CREATE DATABASE tk_legacy",
                "ALTER DATABASE tk_legacy SET ALLOW_SNAPSHOT_ISOLATION ON",
                "CREATE LOGIN tk_source WITH PASSWORD = '" + PASSWORD + "'");
        admin(
                "tk_legacy",
                "CREATE TABLE dbo.people (user_id VARCHAR(10), name NVARCHAR(50), mail NVARCHAR(50))",
                "INSERT INTO dbo.people VALUES ('u1', 'Synthetic One', 'one@example.test'),"
                        + " ('u2', 'Synthetic Two', 'two@example.test')",
                "CREATE TABLE dbo.cases (case_id VARCHAR(10), subject NVARCHAR(50), details NVARCHAR(MAX),"
                        + " state VARCHAR(10), opened DATETIME, found_on DATE, owner VARCHAR(10))",
                "INSERT INTO dbo.cases (case_id, subject, state) VALUES ('c1', 'Synthetic case one', 'Open'),"
                        + " ('c2', 'Synthetic case c2', 'Closed'), ('c3', 'Synthetic case c3', 'Weird')",
                "UPDATE dbo.cases SET details = 'Synthetic details', opened = '2019-03-01T09:30:00',"
                        + " found_on = '2019-02-28', owner = 'u1'",
                "CREATE TABLE dbo.notes (note_id VARCHAR(10), case_id VARCHAR(10), author VARCHAR(10),"
                        + " created DATETIME2, body NTEXT)",
                "INSERT INTO dbo.notes VALUES ('n1', 'c1', 'u2', '2019-03-02T10:00:00', 'Synthetic note'),"
                        + " ('n2', 'c1', 'u2', '2019-03-02T10:00:00', 'Synthetic note'),"
                        + " ('n2', 'c1', 'u2', '2019-03-02T10:00:00', 'Synthetic note')",
                "CREATE TABLE dbo.files (file_id VARCHAR(10), case_id VARCHAR(10), name NVARCHAR(50), bytes BIGINT,"
                        + " content IMAGE)",
                "CREATE TABLE dbo.typed (id INT, opened DATETIME, at_ts DATETIME2, small SMALLDATETIME,"
                        + " zoned DATETIMEOFFSET, day DATE, amount DECIMAL(10,2), body NVARCHAR(MAX), old_text NTEXT,"
                        + " content VARBINARY(MAX), picture IMAGE, flag BIT)",
                "INSERT INTO dbo.typed VALUES (100, '2019-03-04T09:00:00.250', '2019-03-04T09:00:00.1234567',"
                        + " '2019-03-04T09:00:00', '2019-06-01T09:30:00+02:00', '2019-02-28', 12.50,"
                        + " 'Synthetic long text', 'Synthetic old text', 0xCAFE, 0xBEEF, 1)",
                "CREATE TABLE dbo.secrets (id INT)",
                "CREATE SYNONYM dbo.tk_leak FOR dbo.secrets",
                "CREATE SEQUENCE dbo.ids START WITH 1",
                "CREATE FUNCTION dbo.leak (@n INT) RETURNS INT AS BEGIN RETURN @n END",
                "CREATE FUNCTION dbo.rows_of () RETURNS TABLE AS RETURN SELECT id FROM dbo.secrets",
                "CREATE PROCEDURE dbo.touch AS INSERT INTO dbo.secrets VALUES (1)",
                // The recipe, as docs/importer/source-accounts.md gives it.
                "CREATE USER tk_source FOR LOGIN tk_source",
                "GRANT SELECT ON dbo.people TO tk_source",
                "GRANT SELECT ON dbo.cases TO tk_source",
                "GRANT SELECT ON dbo.notes TO tk_source",
                "GRANT SELECT ON dbo.files TO tk_source",
                "GRANT SELECT ON dbo.typed TO tk_source");
        try (Connection admin = adminLogin("tk_legacy");
                PreparedStatement file =
                        admin.prepareStatement("INSERT INTO dbo.files VALUES ('f1', 'c1', 'scan.pdf', 2048, ?)")) {
            file.setBytes(1, SCAN);
            file.executeUpdate();
        }
    }

    @AfterEach
    void dropLegacyDatabase() throws SQLException {
        admin(
                "master",
                "IF DB_ID('tk_legacy') IS NOT NULL ALTER DATABASE tk_legacy SET SINGLE_USER WITH ROLLBACK IMMEDIATE",
                "IF DB_ID('tk_legacy') IS NOT NULL DROP DATABASE tk_legacy",
                "IF SUSER_ID('tk_source') IS NOT NULL DROP LOGIN tk_source");
    }

    @Test
    void valuesArriveTypedInOneSnapshotThatTakesNoLocks() throws SQLException {
        List<Map<String, @Nullable Object>> rows = new ArrayList<>();
        String first;
        try (Session session = dao.open(source())) {
            dao.stream(session, query("SELECT * FROM dbo.typed WHERE id = ?"), List.of(100), TIMEOUT, rows::add);
            try (Connection admin = adminLogin("tk_legacy");
                    Statement statement = admin.createStatement()) {
                // Fails at once if the reading session holds any lock on the table.
                statement.execute("SET LOCK_TIMEOUT 0");
                statement.execute("UPDATE dbo.typed SET amount = 99 WHERE id = 100");
                statement.execute("INSERT INTO dbo.typed (id) VALUES (101)");
            }
            List<Map<String, @Nullable Object>> later = new ArrayList<>();
            dao.stream(session, query("SELECT id, amount FROM dbo.typed"), List.of(), TIMEOUT, later::add);
            assertThat(later)
                    .as("rows written after the snapshot are not seen")
                    .singleElement()
                    .satisfies(row -> assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("12.50"));
            first = dao.fingerprint(session);
        }
        try (Session session = dao.open(source())) {
            assertThat(dao.fingerprint(session))
                    .as("a new session is a new snapshot")
                    .matches("sqlserver:[0-9a-f]{64}")
                    .isNotEqualTo(first);
        }

        Map<String, @Nullable Object> row = rows.getFirst();
        assertThat(row.get("opened")).isEqualTo(LocalDateTime.of(2019, 3, 4, 9, 0, 0, 250_000_000));
        assertThat(row.get("at_ts")).isEqualTo(LocalDateTime.of(2019, 3, 4, 9, 0, 0, 123_456_700));
        assertThat(row.get("small")).isEqualTo(LocalDateTime.of(2019, 3, 4, 9, 0));
        assertThat(Objects.requireNonNull((OffsetDateTime) row.get("zoned")).toInstant())
                .isEqualTo(OffsetDateTime.parse("2019-06-01T07:30:00Z").toInstant());
        assertThat(row.get("day")).isEqualTo(LocalDate.of(2019, 2, 28));
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("12.50");
        assertThat(row.get("body")).isEqualTo("Synthetic long text");
        assertThat(row.get("old_text")).isEqualTo("Synthetic old text");
        assertThat((byte[]) row.get("content")).containsExactly(0xCA, 0xFE);
        assertThat((byte[]) row.get("picture")).containsExactly(0xBE, 0xEF);
        assertThat(row.get("flag")).isEqualTo(true);
    }

    /** What the recipe achieves, as the source account over plain JDBC: no writes, calls or ungranted reads. */
    @Test
    void theAccountCannotWriteCallOrReadAnUngrantedObject() throws SQLException {
        try (Connection account = DriverManager.getConnection(url(), "tk_source", PASSWORD);
                Statement statement = account.createStatement()) {
            for (String refused : List.of(
                    "INSERT INTO dbo.cases (case_id) VALUES ('x')",
                    "UPDATE dbo.cases SET state = 'x'",
                    "DELETE FROM dbo.cases",
                    "CREATE TABLE dbo.owned (id INT)",
                    "EXEC dbo.touch",
                    "SELECT dbo.leak(1)",
                    "SELECT id FROM dbo.rows_of()",
                    "SELECT id FROM rows_of()",
                    "SELECT id FROM tk_leak",
                    "SELECT NEXT VALUE FOR dbo.ids")) {
                assertThatThrownBy(() -> statement.execute(refused))
                        .as(refused)
                        .isInstanceOfSatisfying(
                                SQLException.class,
                                e -> assertThat(e.getErrorCode()).isIn(229, 262));
            }
        }
        try (Connection admin = adminLogin("tk_legacy");
                Statement statement = admin.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM dbo.secrets")) {
            rows.next();
            assertThat(rows.getInt(1)).as("nothing written").isZero();
        }
    }

    /**
     * The validator's part: no locking or snapshot-escaping hint, no call outside the allow-list, qualified or not, no
     * table function, no linked server and no sequence. An unqualified synonym reads like a table, so it is the
     * recipe's job: through the DAO it is refused with the code alone, and the snapshot carries on.
     */
    @Test
    void theValidatorRefusesHintsCallsAndLinksAndTheRecipeTheRest() {
        for (String refused : List.of(
                "SELECT id FROM dbo.typed WITH (UPDLOCK)",
                "SELECT id FROM dbo.typed WITH (HOLDLOCK)",
                "SELECT id FROM dbo.typed WITH (TABLOCKX)",
                "SELECT id FROM dbo.typed WITH (NOLOCK)",
                "SELECT dbo.leak(id) FROM dbo.typed",
                "SELECT leak(id) FROM dbo.typed",
                "SELECT id FROM rows_of()",
                "SELECT * FROM OPENQUERY(remote, 'SELECT 1')",
                "SELECT id FROM remote.tk_legacy.dbo.typed",
                "SELECT NEXT VALUE FOR dbo.ids",
                "EXEC dbo.touch")) {
            assertThat(SourceQueryValidator.validate(refused, Vendor.SQLSERVER).query())
                    .as(refused)
                    .isNull();
        }
        try (Session session = dao.open(source())) {
            for (String ungranted : List.of("SELECT id FROM dbo.secrets", "SELECT id FROM tk_leak")) {
                assertThatThrownBy(() -> dao.testFetch(session, query(ungranted), TIMEOUT))
                        .as(ungranted)
                        .isInstanceOfSatisfying(SourceQueryException.class, e -> {
                            assertThat(e.code()).isEqualTo("SOURCE_PERMISSION_DENIED");
                            assertThat(e.getMessage()).doesNotContain("secrets");
                        });
            }
            assertThat(dao.testFetch(session, query("SELECT id FROM dbo.typed"), TIMEOUT))
                    .as("a refused query does not end the snapshot transaction")
                    .isOne();
        }
    }

    /**
     * The recipe's check that nothing in the legacy database is granted to {@code public}, which every account is a
     * member of, finds such a grant; and without {@code ALLOW_SNAPSHOT_ISOLATION} nothing is read at all.
     */
    @Test
    void theRecipeFindsPublicGrantsAndNothingIsReadWithoutSnapshotIsolation() throws SQLException {
        admin("tk_legacy", "GRANT SELECT ON dbo.secrets TO public");
        try (Connection admin = adminLogin("tk_legacy");
                Statement statement = admin.createStatement();
                ResultSet rows = statement.executeQuery(SourceAccountsRecipe.PUBLIC_GRANTS)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("secrets");
        }

        admin("master", "ALTER DATABASE tk_legacy SET ALLOW_SNAPSHOT_ISOLATION OFF");
        try (Session session = dao.open(source())) {
            assertThatThrownBy(() -> dao.testFetch(session, query("SELECT id FROM dbo.typed"), TIMEOUT))
                    .isInstanceOfSatisfying(
                            SourceQueryException.class,
                            e -> assertThat(e.code()).isEqualTo("SOURCE_QUERY_FAILED"));
        }
    }

    @Test
    void validateMappingTestFetchesAndExtractStagesWithTheImageInTheContentArea(@TempDir Path directory)
            throws Exception {
        String text = LegacyPostgresSource.mapping(MAPPING)
                .replace("vendor: postgresql", "vendor: sqlserver")
                .replace("legacy.", "dbo.");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("import.source.url", url())
                .withProperty("import.source.user", "tk_source")
                .withProperty("import.source.password", PASSWORD);
        assertThat(new SourceTestFetch(dao, environment).check(text.getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
        assertThat(new SourceTestFetch(dao, environment.withProperty("import.source.password", "wrong"))
                        .check(text.getBytes(StandardCharsets.UTF_8)))
                .containsExactly(new MappingProblem(MappingProblem.Code.SOURCE_UNAVAILABLE, "/source"));

        Path mapping = mapping(directory, text);
        Path area = Files.createDirectories(directory.resolve("content"));
        long run = validatedRun(mapping);
        extract(
                        run,
                        mapping,
                        null,
                        "--import.source.url=" + url(),
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
                .matches("sqlserver:[0-9a-f]{64}");
        String sha256 = ValidateMappingCommand.sha256(SCAN);
        assertThat(jdbc.queryForObject(
                        "SELECT CONTENT_POINTER FROM IMPORT_STG_ATTACHMENT WHERE RUN_ID = ?", String.class, run))
                .isEqualTo(sha256);
        assertThat(area.resolve(Long.toString(run)).resolve(sha256)).hasBinaryContent(SCAN);
    }

    /** The recipe's statements that the test runs verbatim. */
    private static final class SourceAccountsRecipe {

        /** docs/importer/source-accounts.md, SQL Server: legacy objects granted to {@code public}. */
        static final String PUBLIC_GRANTS = "SELECT o.name, p.permission_name FROM sys.database_permissions p"
                + " JOIN sys.objects o ON o.object_id = p.major_id"
                + " WHERE p.grantee_principal_id = DATABASE_PRINCIPAL_ID('public') AND o.is_ms_shipped = 0";
    }

    /** The source account's URL; TLS is off only because the test container has no certificate to verify. */
    private static String url() {
        return "jdbc:sqlserver://" + LEGACY.getHost() + ":" + LEGACY.getMappedPort(1433)
                + ";databaseName=tk_legacy;encrypt=false";
    }

    private Source source() {
        return new Source(url(), "tk_source", PASSWORD, Vendor.SQLSERVER);
    }

    private static ValidatedSourceQuery query(String sql) {
        SourceQueryValidator.Result result = SourceQueryValidator.validate(sql, Vendor.SQLSERVER);
        return Objects.requireNonNull(result.query(), () -> "refused: " + result.problems());
    }

    private static Connection adminLogin(String database) throws SQLException {
        return DriverManager.getConnection(
                "jdbc:sqlserver://" + LEGACY.getHost() + ":" + LEGACY.getMappedPort(1433) + ";databaseName=" + database
                        + ";encrypt=false",
                LEGACY.getUsername(),
                LEGACY.getPassword());
    }

    private static void admin(String database, String... statements) throws SQLException {
        try (Connection admin = adminLogin(database);
                Statement statement = admin.createStatement()) {
            for (String sql : statements) {
                // Test-only DDL and seed rows, fixed in this class.
                statement.execute(sql);
            }
        }
    }
}
