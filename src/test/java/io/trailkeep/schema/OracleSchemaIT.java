// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.shared.persistence.DatabaseVendor;
import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The schema-validation harness on Oracle. Runs only under {@code -Poracle-it}, once per image: the profile's two
 * Failsafe executions set {@value OracleTestcontainersConfiguration#IMAGE_PROPERTY} to
 * {@code gvenzl/oracle-xe:21-slim} and {@code gvenzl/oracle-free:23-slim}. A deploying organization points the same
 * property at its own image. The dialect version is NOT taken from the image: both runs use the pin in
 * {@code application-oracle.yml} (21.0), so the 23 server is validated against 21-era DDL expectations — the
 * ADR-0004 claim that a newer server never changes what the build expects.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleSchemaIT extends AbstractVendorSchemaIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The oracle-test profile group carries no router placeholder of its own; AppProperties requires one. */
    @DynamicPropertySource
    static void proxyPattern(DynamicPropertyRegistry registry) {
        registry.add("app.proxy.pattern", () -> "10\\.128\\.\\d+\\.\\d+");
    }

    @Test
    void migrationsRunWithCharacterLengthSemantics() {
        // 'C' only if Flyway's initSql (NLS_LENGTH_SEMANTICS=CHAR) applied; the images default to BYTE semantics.
        String charUsed = jdbcTemplate.queryForObject(
                "SELECT CHAR_USED FROM USER_TAB_COLUMNS WHERE TABLE_NAME = 'LINT_PROBE' AND COLUMN_NAME = 'NAME'",
                String.class);

        assertThat(charUsed).isEqualTo("C");
    }

    @Override
    DatabaseVendor vendor() {
        return DatabaseVendor.ORACLE;
    }

    @Override
    String dropNotNull(String table, String column) {
        return "ALTER TABLE " + table + " MODIFY (" + column + " NULL)";
    }

    @Override
    String setNotNull(String table, String column) {
        return "ALTER TABLE " + table + " MODIFY (" + column + " NOT NULL)";
    }

    @Override
    String resizeVarchar(String table, String column, int length) {
        return "ALTER TABLE " + table + " MODIFY (" + column + " VARCHAR2(" + length + " CHAR))";
    }
}
