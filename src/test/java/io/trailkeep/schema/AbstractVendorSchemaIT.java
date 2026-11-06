// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.shared.persistence.DatabaseProperties;
import io.trailkeep.shared.persistence.DatabaseVendor;
import io.trailkeep.testsupport.RequiresDocker;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.hibernate.SessionFactory;
import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * The schema-validation harness every vendor runs (WP-03a). A concrete subclass supplies the container (through an
 * imported {@code @ServiceConnection} configuration), the active profile, and three vendor DDL statements used only
 * to plant drift. The application context itself has already run Flyway and Hibernate {@code validate}; the tests
 * make each step explicit and prove the metadata diff is not vacuous.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractVendorSchemaIT {

    private static final String PROBE_TABLE = "LINT_PROBE";
    private static final String PROBE_COLUMN = "NAME";
    private static final int PROBE_LENGTH = 50;

    @Autowired
    private Flyway flyway;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DatabaseProperties databaseProperties;

    @Autowired
    private Environment environment;

    /** The vendor this subclass runs against. */
    abstract DatabaseVendor vendor();

    /** DDL making {@code table.column} nullable. */
    abstract String dropNotNull(String table, String column);

    /** DDL making {@code table.column} NOT NULL again. */
    abstract String setNotNull(String table, String column);

    /** DDL changing a character column's declared length. */
    abstract String resizeVarchar(String table, String column, int length);

    @Test
    void connectsToTheConfiguredVendor() throws SQLException {
        assertThat(databaseProperties.vendor()).isEqualTo(vendor());
        try (Connection connection = dataSource.getConnection()) {
            assertThat(vendor().matches(connection.getMetaData().getDatabaseProductName()))
                    .isTrue();
        }
    }

    @Test
    void dialectVersionIsPinnedByConfigurationNotByTheServer() {
        DatabaseVersion version = entityManagerFactory
                .unwrap(SessionFactoryImplementor.class)
                .getJdbcServices()
                .getDialect()
                .getVersion();
        String prefix = "spring.jpa.properties.jakarta.persistence.";

        assertThat(version.getDatabaseMajorVersion())
                .isEqualTo(environment.getRequiredProperty(prefix + "database-major-version", Integer.class));
        assertThat(version.getDatabaseMinorVersion())
                .isEqualTo(environment.getRequiredProperty(prefix + "database-minor-version", Integer.class));
    }

    @Test
    void flywayAppliedEveryMigrationAndValidates() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(Arrays.stream(flyway.info().applied()).map(MigrationInfo::getScript))
                .contains("V0_1__lint_probe.sql");
        flyway.validate();
    }

    @Test
    void hibernateValidateAcceptsTheMigratedSchema() {
        entityManagerFactory.unwrap(SessionFactory.class).getSchemaManager().validateMappedObjects();
    }

    @Test
    void metadataDiffCoversTheProbeEntity() {
        assertThat(SchemaMetadataDiff.expectedColumns(entityManagerFactory))
                .containsEntry(
                        PROBE_TABLE + "." + PROBE_COLUMN,
                        new SchemaMetadataDiff.ExpectedColumn(PROBE_TABLE, PROBE_COLUMN, false, (long) PROBE_LENGTH))
                .containsKeys(PROBE_TABLE + ".ID", PROBE_TABLE + ".ACTIVE", PROBE_TABLE + ".NOTE");
    }

    @Test
    void identityMigrationAndGrantsAreApplied() {
        assertThat(Arrays.stream(flyway.info().applied()).map(MigrationInfo::getScript))
                .contains("V1__identity.sql", "R__grants.sql");
    }

    @Test
    void metadataDiffCoversTheIdentityEntities() {
        assertThat(SchemaMetadataDiff.expectedColumns(entityManagerFactory))
                .containsEntry("APP_USER.EXTERNAL_ID", column("APP_USER", "EXTERNAL_ID", false, 255L))
                .containsEntry("APP_USER.EMAIL", column("APP_USER", "EMAIL", false, 320L))
                .containsEntry("TEAM.NAME", column("TEAM", "NAME", false, 120L))
                .containsEntry("APP_USER_TEAM.ROLE_IN_TEAM", column("APP_USER_TEAM", "ROLE_IN_TEAM", false, 10L))
                .containsEntry(
                        "APP_USER_GLOBAL_ROLE.GLOBAL_ROLE", column("APP_USER_GLOBAL_ROLE", "GLOBAL_ROLE", false, 16L))
                .containsKeys(
                        "APP_USER.ID",
                        "APP_USER.LAST_LOGIN_AT",
                        "APP_USER.DISABLED",
                        "APP_USER.CREATED_AT",
                        "TEAM.ACTIVE",
                        "APP_USER_TEAM.USER_ID",
                        "APP_USER_TEAM.TEAM_ID",
                        "APP_USER_GLOBAL_ROLE.USER_ID");
    }

    @Test
    void lastLoginIsTheOnlyNullableIdentityColumn() {
        assertThat(nullableColumns(Set.of("APP_USER", "TEAM", "APP_USER_TEAM", "APP_USER_GLOBAL_ROLE")))
                .containsExactly("APP_USER.LAST_LOGIN_AT");
    }

    @Test
    void auditEventNullabilityMatchesV2() {
        assertThat(nullableColumns(Set.of("AUDIT_EVENT")))
                .containsExactlyInAnyOrder(
                        "AUDIT_EVENT.ACTOR_ID",
                        "AUDIT_EVENT.ACTOR_NAME_SNAPSHOT",
                        "AUDIT_EVENT.CASE_ID",
                        "AUDIT_EVENT.ENTITY_ID",
                        "AUDIT_EVENT.OLD_VALUES_JSON",
                        "AUDIT_EVENT.NEW_VALUES_JSON",
                        "AUDIT_EVENT.REQUEST_ID",
                        "AUDIT_EVENT.CLIENT_IP");
    }

    private List<String> nullableColumns(Set<String> tables) {
        return SchemaMetadataDiff.expectedColumns(entityManagerFactory).values().stream()
                .filter(column -> tables.contains(column.table()) && column.nullable())
                .map(column -> column.table() + "." + column.column())
                .toList();
    }

    @Test
    void teamMembershipHasTheReverseIndexForItsTeamForeignKey() throws SQLException {
        List<String> indexes = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String table = metaData.storesLowerCaseIdentifiers() ? "app_user_team" : "APP_USER_TEAM";
            try (ResultSet rows = metaData.getIndexInfo(null, databaseProperties.schema(), table, false, true)) {
                while (rows.next()) {
                    String name = rows.getString("INDEX_NAME");
                    if (name != null && rows.getShort("ORDINAL_POSITION") == 1) {
                        indexes.add(name.toUpperCase(Locale.ROOT) + ":"
                                + rows.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
                    }
                }
            }
        }

        assertThat(indexes).contains("IX_AUT_TEAM_USER:TEAM_ID", "PK_APP_USER_TEAM:USER_ID");
    }

    @Test
    void savedViewsAreMigratedWithTheirEntityAndEachPersonsNamesAreUnique() throws SQLException {
        assertThat(Arrays.stream(flyway.info().applied()).map(MigrationInfo::getScript))
                .contains("V4_7__saved_view.sql");
        assertThat(SchemaMetadataDiff.expectedColumns(entityManagerFactory))
                .containsEntry("SAVED_VIEW.NAME", column("SAVED_VIEW", "NAME", false, 100L))
                .containsEntry("SAVED_VIEW.QUERY", column("SAVED_VIEW", "QUERY", false, 1000L))
                .containsKeys("SAVED_VIEW.ID", "SAVED_VIEW.USER_ID", "SAVED_VIEW.CREATED_AT");
        assertThat(nullableColumns(Set.of("SAVED_VIEW"))).isEmpty();
        List<String> unique = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String table = metaData.storesLowerCaseIdentifiers() ? "saved_view" : "SAVED_VIEW";
            try (ResultSet rows = metaData.getIndexInfo(null, databaseProperties.schema(), table, true, true)) {
                while (rows.next()) {
                    String name = rows.getString("INDEX_NAME");
                    if (name != null) {
                        unique.add(name.toUpperCase(Locale.ROOT) + ":" + rows.getShort("ORDINAL_POSITION") + ":"
                                + rows.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
                    }
                }
            }
        }

        assertThat(unique).contains("UK_SAVED_VIEW_USER_NAME:1:USER_ID", "UK_SAVED_VIEW_USER_NAME:2:NAME");
    }

    private static SchemaMetadataDiff.ExpectedColumn column(
            String table, String column, boolean nullable, @Nullable Long length) {
        return new SchemaMetadataDiff.ExpectedColumn(table, column, nullable, length);
    }

    @Test
    void metadataDiffFindsNoDriftWhenEntitiesAndSchemaAgree() throws SQLException {
        assertThat(diff()).isEmpty();
    }

    @Test
    void metadataDiffReportsPlantedNullabilityMismatch() throws SQLException {
        execute(dropNotNull(PROBE_TABLE, PROBE_COLUMN));
        try {
            assertThat(diff())
                    .containsExactly(new ColumnMismatch(
                            PROBE_TABLE, PROBE_COLUMN, ColumnMismatch.Kind.NULLABILITY, "NOT NULL", "NULL"));
        } finally {
            execute(setNotNull(PROBE_TABLE, PROBE_COLUMN));
        }
        assertThat(diff()).isEmpty();
    }

    @Test
    void metadataDiffReportsPlantedLengthMismatch() throws SQLException {
        execute(resizeVarchar(PROBE_TABLE, PROBE_COLUMN, PROBE_LENGTH + 10));
        try {
            assertThat(diff())
                    .containsExactly(
                            new ColumnMismatch(PROBE_TABLE, PROBE_COLUMN, ColumnMismatch.Kind.LENGTH, "50", "60"));
        } finally {
            execute(resizeVarchar(PROBE_TABLE, PROBE_COLUMN, PROBE_LENGTH));
        }
        assertThat(diff()).isEmpty();
    }

    private List<ColumnMismatch> diff() throws SQLException {
        return SchemaMetadataDiff.diff(entityManagerFactory, dataSource, databaseProperties.schema());
    }

    // Test-only DDL to plant drift; production code never issues DDL (the migration tool owns the schema).
    private void execute(String ddl) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }
}
