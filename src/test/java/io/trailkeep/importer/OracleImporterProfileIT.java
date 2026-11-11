// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The two persistence units on Oracle ({@code -Poracle-it}, both images): the importer as the import user and the web
 * application as the runtime user each pass Hibernate's {@code validate}, which on Oracle sees only granted objects.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleImporterProfileIT extends AbstractImporterProfileIT {

    @Override
    String vendorProfile() {
        return "oracle-test";
    }
}
