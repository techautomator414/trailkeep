// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The SLA evaluator on Oracle ({@code -Poracle-it}, both images): the JPQL row lock over an id list ({@code FOR
 * UPDATE} with no row limit), the bulk updates and the ledger's unique key.
 */
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleSlaEvaluatorIT extends AbstractSlaEvaluatorIT {}
