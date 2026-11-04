// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The shared back-channel registry on Oracle. Runs only under {@code -Poracle-it}, once per image (XE 21 and Free 23),
 * and proves what PostgreSQL cannot: an {@code OffsetDateTime} bound to {@code TIMESTAMP WITH TIME ZONE}, a
 * {@code CLOB} read with {@code getString}, and a {@code CHAR(36)} session id compared with a {@code VARCHAR} bind.
 * Every future {@code *JdbcDao} test follows this shape: shared abstract class, one subclass per vendor.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleJdbcOidcSessionRegistryIT extends AbstractJdbcOidcSessionRegistryIT {

    /** The oracle-test profile group carries no router placeholder of its own; AppProperties requires one. */
    @DynamicPropertySource
    static void proxyPattern(DynamicPropertyRegistry registry) {
        registry.add("app.proxy.pattern", () -> "10\\.128\\.\\d+\\.\\d+");
    }
}
