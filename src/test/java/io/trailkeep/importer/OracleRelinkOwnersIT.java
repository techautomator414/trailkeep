// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code relink-owners} on Oracle ({@code -Poracle-it}, both images), as the import user: the owner change, its audit
 * event, the pending rows it deletes and the run's figures and progress are all writes the import user may make there.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleRelinkOwnersIT extends AbstractRelinkOwnersIT {

    @Override
    String vendorProfile() {
        return "oracle-test";
    }
}
