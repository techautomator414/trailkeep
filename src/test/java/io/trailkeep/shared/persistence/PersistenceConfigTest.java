// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.hibernate.cfg.MappingSettings;
import org.junit.jupiter.api.Test;

class PersistenceConfigTest {

    private final PersistenceConfig config = new PersistenceConfig();

    @Test
    void migrationIsRefusedWhenTheConfiguredVendorIsNotTheConnectedOne() throws SQLException {
        FluentConfiguration flyway = new FluentConfiguration().dataSource(dataSourceReporting("Oracle"));
        DatabaseProperties postgres = new DatabaseProperties(DatabaseVendor.POSTGRESQL, null);

        assertThatIllegalStateException()
                .isThrownBy(() -> config.vendorGuard(postgres).customize(flyway))
                .withMessageContaining("app.db.vendor is POSTGRESQL but the DataSource is Oracle");
    }

    @Test
    void migrationProceedsWhenTheVendorsAgree() throws SQLException {
        FluentConfiguration flyway = new FluentConfiguration().dataSource(dataSourceReporting("PostgreSQL"));
        DatabaseProperties postgres = new DatabaseProperties(DatabaseVendor.POSTGRESQL, null);

        assertThatCode(() -> config.vendorGuard(postgres).customize(flyway)).doesNotThrowAnyException();
    }

    @Test
    void defaultSchemaIsSetOnlyWhenConfigured() {
        Map<String, Object> withSchema = new HashMap<>();
        Map<String, Object> blank = new HashMap<>();

        config.defaultSchemaCustomizer(new DatabaseProperties(DatabaseVendor.ORACLE, "TRAILKEEP_OWNER"))
                .customize(withSchema);
        config.defaultSchemaCustomizer(new DatabaseProperties(DatabaseVendor.ORACLE, " "))
                .customize(blank);

        assertThat(withSchema).containsEntry(MappingSettings.DEFAULT_SCHEMA, "TRAILKEEP_OWNER");
        assertThat(blank).doesNotContainKey(MappingSettings.DEFAULT_SCHEMA);
    }

    @Test
    void vendorMatchesTheDriverProductName() {
        assertThat(DatabaseVendor.ORACLE.matches("Oracle")).isTrue();
        assertThat(DatabaseVendor.POSTGRESQL.matches("PostgreSQL")).isTrue();
        assertThat(DatabaseVendor.ORACLE.matches("PostgreSQL")).isFalse();
    }

    private static DataSource dataSourceReporting(String productName) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn(productName);
        return dataSource;
    }
}
