// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The two persistence units on PostgreSQL. Its driver reads {@code pg_catalog}, which lists every table, so the
 * Oracle run ({@link OracleImporterProfileIT}) is the one that proves the grants; this one still proves the sequences,
 * which Hibernate reads from {@code information_schema} and sees only with a privilege.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
class ImporterProfileIT extends AbstractImporterProfileIT {

    @Override
    String vendorProfile() {
        return "test";
    }
}
