// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code load} on Oracle ({@code -Poracle-it}, both images), as the import user: every row the load writes (case, SLA
 * row, search row, closure, audit events, ledger row and the run's progress) is one the import user may write there.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleLoadIT extends AbstractLoadIT {

    @Override
    String vendorProfile() {
        return "oracle-test";
    }
}
