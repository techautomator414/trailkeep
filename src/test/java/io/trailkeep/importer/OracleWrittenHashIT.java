// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The import ledger's written hashes on Oracle ({@code -Poracle-it}, both images), the load as the import user with
 * {@code app.db.schema} set, as the Job runs: Oracle stores an empty string as {@code NULL} and instants to the
 * microsecond, and the hashes still agree because the forms are read from the database.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleWrittenHashIT extends AbstractWrittenHashIT {

    @Override
    String vendorProfile() {
        return "oracle-test";
    }
}
