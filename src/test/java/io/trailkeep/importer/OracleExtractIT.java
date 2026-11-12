// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code extract} on Oracle ({@code -Poracle-it}, both images), as the import user, whose grants are its only way to
 * staging: the Criteria deletes and key queries, the long-text columns and the run columns it may update.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleExtractIT extends AbstractExtractIT {

    @Override
    String vendorProfile() {
        return "oracle-test";
    }
}
