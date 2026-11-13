// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code reconcile} on Oracle ({@code -Poracle-it}, both images), as the import user with {@code app.db.schema} set:
 * the written forms read back as stored there, and the issues, figures and run status it replaces are writes the
 * import user may make.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleReconcileIT extends AbstractReconcileIT {

    @Override
    String vendorProfile() {
        return "oracle-test";
    }
}
