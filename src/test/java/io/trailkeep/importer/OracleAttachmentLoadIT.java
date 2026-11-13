// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code load} with attachments on Oracle ({@code -Poracle-it}, both images), as the import user with {@code
 * app.db.schema} set, as the Job runs: the attachment rows, their chunks (a native DAO), audit events, issues and
 * ledger rows are rows the import user may write there.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleAttachmentLoadIT extends AbstractAttachmentLoadIT {

    @Override
    String vendorProfile() {
        return "oracle-test";
    }
}
