// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The importer at the release volume with Trailkeep on Oracle (WP-30 part b): the legacy source is still the
 * PostgreSQL one, and every command runs as the import user with {@code app.db.schema} set. Opt-in, by the {@code
 * perf-it} profile's {@code perf-import-oracle} execution (Free 23 unless {@code -Dtrailkeep.oracle.image=oracle-xe}).
 * The table goes to {@code target/perf/importer-volume-oracle.md}.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("perf-import-oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleImportVolumePerfIT extends AbstractImportVolumePerfIT {

    @Override
    String vendorProfile() {
        return "oracle-test";
    }

    @Override
    ImportVolumeReport report() {
        return new ImportVolumeReport(
                "The importer at the release volume, Trailkeep on Oracle "
                        + OracleTestcontainersConfiguration.imageName() + " (OracleImportVolumePerfIT)",
                "importer-volume-oracle.md");
    }
}
