// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OidcBackChannelLogoutHandler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/** An instance on a random port (server.port=0) registers its real port for back-channel logout, not port 0. */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BackChannelLogoutUriIT {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private OidcBackChannelLogoutHandler handler;

    @Test
    void theLogoutUriCarriesThePortTheServerStartedOn() {
        assertThat(port).isPositive();
        assertThat(ReflectionTestUtils.getField(handler, "logoutUri"))
                .isEqualTo("http://localhost:" + port + "/logout");
    }
}
