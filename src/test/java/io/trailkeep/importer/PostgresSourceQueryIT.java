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
import io.trailkeep.testsupport.RequiresDocker;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The PostgreSQL source (WP-26 part n, design §4.1, §11): the grants recipe of docs/importer/source-accounts.md applied
 * to a synthetic legacy schema in its own container, then {@link SourceQueryJdbcDao} reading as that account, and
 * what the recipe achieves: the account reads its table in one snapshot and cannot write, lock, read an ungranted
 * table or run a legacy function.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
class PostgresSourceQueryIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final PostgreSQLContainer LEGACY =
            new PostgreSQLContainer(PostgresTestcontainersConfiguration.IMAGE);
    private static final String PASSWORD = "Tk" + HexFormat.of().formatHex(new SecureRandom().generateSeed(12));

    private final SourceQueryJdbcDao dao = new SourceQueryJdbcDao();

    @BeforeAll
    static void legacyDatabaseWithTheRecipeApplied() throws SQLException {
        LEGACY.start();
        admin(
                "CREATE SCHEMA legacy",
                "CREATE TABLE legacy.cases (case_id INTEGER PRIMARY KEY, case_no TEXT, opened TIMESTAMP,"
                        + " closed_at TIMESTAMPTZ, found_on DATE, amount NUMERIC(10,2), content BYTEA)",
                "INSERT INTO legacy.cases VALUES (100, 'C-100', '2019-03-04 09:00', '2019-06-01 09:30+00',"
                        + " '2019-03-01', 12.50, '\\xcafe')",
                "CREATE TABLE legacy.secrets (id INTEGER)",
                "CREATE FUNCTION legacy.leak() RETURNS INTEGER LANGUAGE SQL AS 'SELECT 1'",
                // The recipe, as docs/importer/source-accounts.md gives it.
                "CREATE ROLE trailkeep_source LOGIN PASSWORD '" + PASSWORD + "'",
                "GRANT USAGE ON SCHEMA legacy TO trailkeep_source",
                "GRANT SELECT ON legacy.cases TO trailkeep_source",
                "REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA legacy FROM PUBLIC");
    }

    @AfterAll
    static void stop() {
        LEGACY.stop();
    }

    @Test
    void rowsArriveTypedInOneSnapshotAndTheTestFetchReadsOneRowAtMost() throws SQLException {
        List<Map<String, @Nullable Object>> rows = new ArrayList<>();
        try (Session session = dao.open(source())) {
            dao.stream(
                    session,
                    query("SELECT case_id, case_no, opened, closed_at, found_on, amount, content"
                            + " FROM legacy.cases WHERE case_id = ?"),
                    List.of(100),
                    TIMEOUT,
                    rows::add);
            admin("INSERT INTO legacy.cases (case_id, case_no) VALUES (101, 'C-101')");

            assertThat(dao.testFetch(session, query("SELECT case_id FROM legacy.cases"), List.of(), TIMEOUT))
                    .isOne();
            List<Map<String, @Nullable Object>> later = new ArrayList<>();
            dao.stream(session, query("SELECT case_id FROM legacy.cases"), List.of(), TIMEOUT, later::add);
            assertThat(later)
                    .as("a row committed after the snapshot is not seen")
                    .hasSize(1);
            assertThat(dao.fingerprint(session)).isNotBlank();
        } finally {
            admin("DELETE FROM legacy.cases WHERE case_id = 101");
        }

        Map<String, @Nullable Object> row = rows.getFirst();
        assertThat(row.get("case_no")).isEqualTo("C-100");
        assertThat(row.get("opened")).isEqualTo(LocalDateTime.of(2019, 3, 4, 9, 0));
        assertThat(Objects.requireNonNull((OffsetDateTime) row.get("closed_at")).toInstant())
                .isEqualTo(OffsetDateTime.parse("2019-06-01T09:30:00Z").toInstant());
        assertThat(row.get("found_on")).isEqualTo(LocalDate.of(2019, 3, 1));
        assertThat(row.get("amount")).isEqualTo(new BigDecimal("12.50"));
        assertThat((byte[]) row.get("content")).containsExactly(0xCA, 0xFE);
    }

    @Test
    void anUngrantedTableIsAPermissionCodeThatQuotesNothing() {
        try (Session session = dao.open(source())) {
            assertThatThrownBy(() -> dao.testFetch(session, query("SELECT id FROM legacy.secrets"), List.of(), TIMEOUT))
                    .isInstanceOfSatisfying(SourceQueryException.class, e -> {
                        assertThat(e.code()).isEqualTo("SOURCE_PERMISSION_DENIED");
                        assertThat(e.getMessage()).doesNotContain("secrets");
                        assertThat(e.getCause()).isNull();
                    });
        }
    }

    /** What the recipe achieves, statement by statement, as the source account over plain JDBC. */
    @Test
    void theAccountCannotWriteLockOrRunALegacyFunction() throws SQLException {
        try (Connection account = DriverManager.getConnection(LEGACY.getJdbcUrl(), "trailkeep_source", PASSWORD);
                Statement statement = account.createStatement()) {
            for (String refused : List.of(
                    "INSERT INTO legacy.cases (case_id) VALUES (1)",
                    "UPDATE legacy.cases SET case_no = 'x'",
                    "DELETE FROM legacy.cases",
                    "SELECT case_id FROM legacy.cases FOR UPDATE",
                    "SELECT legacy.leak()")) {
                assertThatThrownBy(() -> statement.execute(refused))
                        .as(refused)
                        .isInstanceOfSatisfying(
                                SQLException.class,
                                e -> assertThat(e.getSQLState()).isEqualTo("42501"));
            }
        }
        assertThat(SourceQueryValidator.validate("SELECT legacy.leak()", Vendor.POSTGRESQL)
                        .problems())
                .as("and the validator refuses the call before it is ever sent")
                .contains(MappingProblem.Code.QUERY_FUNCTION_NOT_ALLOWED);
    }

    private static Source source() {
        return new Source(LEGACY.getJdbcUrl(), "trailkeep_source", PASSWORD, Vendor.POSTGRESQL);
    }

    private static ValidatedSourceQuery query(String sql) {
        SourceQueryValidator.Result result = SourceQueryValidator.validate(sql, Vendor.POSTGRESQL);
        return Objects.requireNonNull(result.query(), () -> "refused: " + result.problems());
    }

    private static void admin(String... statements) throws SQLException {
        try (Connection admin =
                        DriverManager.getConnection(LEGACY.getJdbcUrl(), LEGACY.getUsername(), LEGACY.getPassword());
                Statement statement = admin.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }
}
