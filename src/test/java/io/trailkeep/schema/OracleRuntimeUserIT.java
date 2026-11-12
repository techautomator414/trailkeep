// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The web application as the runtime user on Oracle ({@code -Poracle-it}, both images), where an unqualified name
 * resolves in the connecting user's own schema unless the session's {@code CURRENT_SCHEMA} says otherwise: without it,
 * every native statement fails with ORA-00942 or ORA-02289.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
class OracleRuntimeUserIT extends AbstractRuntimeUserIT {

    @Override
    String vendorProfile() {
        return "oracle-test";
    }
}
