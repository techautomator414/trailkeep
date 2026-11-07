// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The reports on Oracle ({@code -Poracle-it}, both images): the grouped {@code SUM(CASE WHEN … THEN 1 ELSE 0 END)}
 * counts with the scope's predicate in the same statement, {@code DATE} age edges and {@code TIMESTAMP WITH TIME
 * ZONE} period edges.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleReportIT extends AbstractReportIT {}
