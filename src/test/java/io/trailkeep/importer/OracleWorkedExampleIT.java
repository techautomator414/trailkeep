// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The worked example with Trailkeep on Oracle ({@code -Poracle-it}, both images): the legacy database is still the
 * page's PostgreSQL one, and every command runs as the import user with {@code app.db.schema} set.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleWorkedExampleIT extends AbstractWorkedExampleIT {

    @Override
    String vendorProfile() {
        return "oracle-test";
    }
}
