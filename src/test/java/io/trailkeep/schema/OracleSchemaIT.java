// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.shared.persistence.DatabaseVendor;
import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * property at its own image. The pinned dialect major version follows the image tag unless
 * {@value #MAJOR_VERSION_PROPERTY} is set.
 */
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleSchemaIT extends AbstractVendorSchemaIT {

    static final String MAJOR_VERSION_PROPERTY = "trailkeep.oracle.major-version";

    private static final Pattern TAG_MAJOR = Pattern.compile(":(\\d+)");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void dialectVersion(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.properties.jakarta.persistence.database-major-version", OracleSchemaIT::majorVersion);
    }

    static String majorVersion() {
        String explicit = System.getProperty(MAJOR_VERSION_PROPERTY);
        if (explicit != null) {
            return explicit;
        }
        Matcher matcher = TAG_MAJOR.matcher(OracleTestcontainersConfiguration.imageName());
        if (!matcher.find()) {
            throw new IllegalStateException("Cannot derive the Oracle major version from the image tag; set -D"
                    + MAJOR_VERSION_PROPERTY);
        }
        return matcher.group(1);
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
