// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The importer at the release volume with Trailkeep on PostgreSQL (WP-30 part b), run by the {@code perf-it}
 * profile's {@code perf-import} execution in a JVM and containers of its own. The table goes to {@code
 * target/perf/importer-volume-postgresql.md}.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("perf-import")
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
class PostgresImportVolumePerfIT extends AbstractImportVolumePerfIT {

    @Override
    String vendorProfile() {
        return "test";
    }

    @Override
    ImportVolumeReport report() {
        return new ImportVolumeReport(
                "The importer at the release volume, Trailkeep on PostgreSQL (PostgresImportVolumePerfIT)",
                "importer-volume-postgresql.md");
    }
}
