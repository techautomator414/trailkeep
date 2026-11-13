// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Import holds on Oracle ({@code -Poracle-it}, both images), as the import user with {@code app.db.schema} set: the
 * held case row and its {@code LEGAL_HOLD_EVENT} are writes the import user may make there, and the archive job and
 * the purge user's purge leave the held cases alone.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleImportHoldIT extends AbstractImportHoldIT {

    @Override
    String vendorProfile() {
        return "oracle-test";
    }
}
