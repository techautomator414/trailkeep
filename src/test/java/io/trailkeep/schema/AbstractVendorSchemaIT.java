// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.shared.persistence.DatabaseProperties;
import io.trailkeep.shared.persistence.DatabaseVendor;
import io.trailkeep.testsupport.RequiresDocker;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.hibernate.SessionFactory;
import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.engine.spi.SessionFactoryImplementor;
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
