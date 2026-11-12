// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code dry-run} matching people and default owners on Oracle ({@code -Poracle-it}, both images), as the import user:
 * the users, memberships and roles it may read to match people and check owners.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleDryRunPeopleIT extends AbstractDryRunPeopleIT {

    @Override
    String vendorProfile() {
        return "oracle-test";
    }
}
