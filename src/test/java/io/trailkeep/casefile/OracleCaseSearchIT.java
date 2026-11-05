// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Search-text maintenance on Oracle ({@code -Poracle-it}, both images). Proves what PostgreSQL cannot: the
 * character-stream bind appends over 4000 bytes inside {@code SEARCH_TEXT || ?} without ORA-01461, and the
 * lock-and-rebuild path holds with Oracle's {@code FOR UPDATE}.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleCaseSearchIT extends AbstractCaseSearchIT {}
