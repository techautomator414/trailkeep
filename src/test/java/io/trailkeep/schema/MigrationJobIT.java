// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** The migration Job and the runtime user on PostgreSQL (the Oracle run is {@link OracleMigrationJobIT}). */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
class MigrationJobIT extends AbstractMigrationJobIT {

    @Override
    String vendorProfile() {
        return "test";
    }
}
