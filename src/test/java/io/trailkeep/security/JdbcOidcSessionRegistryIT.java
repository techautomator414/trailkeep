// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** The shared back-channel registry on PostgreSQL (the Oracle run is {@link OracleJdbcOidcSessionRegistryIT}). */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
class JdbcOidcSessionRegistryIT extends AbstractJdbcOidcSessionRegistryIT {}
