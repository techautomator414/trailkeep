// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema;

import io.trailkeep.shared.persistence.DatabaseVendor;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** The schema-validation harness on PostgreSQL 16; runs in every build. */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
class PostgresSchemaIT extends AbstractVendorSchemaIT {

    @Override
    DatabaseVendor vendor() {
        return DatabaseVendor.POSTGRESQL;
    }

    @Override
    String dropNotNull(String table, String column) {
        return "ALTER TABLE " + table + " ALTER COLUMN " + column + " DROP NOT NULL";
    }

    @Override
    String setNotNull(String table, String column) {
        return "ALTER TABLE " + table + " ALTER COLUMN " + column + " SET NOT NULL";
    }

    @Override
    String resizeVarchar(String table, String column, int length) {
        return "ALTER TABLE " + table + " ALTER COLUMN " + column + " TYPE VARCHAR(" + length + ")";
    }
}
