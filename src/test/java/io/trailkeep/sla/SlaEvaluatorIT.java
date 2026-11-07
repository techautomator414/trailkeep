// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** The SLA evaluator on PostgreSQL (the Oracle run is {@link OracleSlaEvaluatorIT}). */
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
class SlaEvaluatorIT extends AbstractSlaEvaluatorIT {}
