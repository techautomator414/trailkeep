// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The reminders tick on Oracle ({@code -Poracle-it}, both images): the paged JPQL candidates with a row limit and no
 * lock, {@code COALESCE} on the reminder times, {@code LIKE} on the ledger's period key and its unique key.
 */
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleRemindersTickIT extends AbstractRemindersTickIT {}
