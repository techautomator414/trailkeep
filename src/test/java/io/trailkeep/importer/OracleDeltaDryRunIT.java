// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code dry-run} of a delta run on Oracle ({@code -Poracle-it}, both images), as the import user with {@code
 * app.db.schema} set, as the Job runs: the ledger it reads is one the import user may read there, and closing times
 * compare equal as the load recorded them.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleDeltaDryRunIT extends AbstractDeltaDryRunIT {

    @Override
    String vendorProfile() {
        return "oracle-test";
    }
}
