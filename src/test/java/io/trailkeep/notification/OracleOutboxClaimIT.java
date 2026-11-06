// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The outbox claim on Oracle ({@code -Poracle-it}, both images): {@code FETCH FIRST … ROWS ONLY} without a lock, then
 * {@code FOR UPDATE SKIP LOCKED} on the {@code IN} list, the statements Oracle accepts where a row-limited
 * {@code FOR UPDATE} is refused.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleOutboxClaimIT extends AbstractOutboxClaimIT {}
