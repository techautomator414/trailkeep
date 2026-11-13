// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The migration Job and the runtime user on Oracle ({@code -Poracle-it}, both images), where the schema owner is a
 * user of its own and the runtime user reaches the tables only through {@code app.db.schema}.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleMigrationJobIT extends AbstractMigrationJobIT {

    @Override
    String vendorProfile() {
        return "oracle-test";
    }
}
