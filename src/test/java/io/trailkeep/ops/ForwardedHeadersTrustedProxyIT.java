// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.ops;

import static io.trailkeep.support.HttpProbe.get;
import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Control for {@link ForwardedHeadersSpoofTest}: when the peer does match {@code app.proxy.pattern}, the forwarded
 * client address and scheme are applied. Without this, the spoof test would also pass if the valve were missing.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "app.proxy.pattern=127\\.0\\.0\\.1"})
@ActiveProfiles("test")
@Import({RemoteAddressController.class, TestOnlyEndpointsSecurity.class, PostgresTestcontainersConfiguration.class})
class ForwardedHeadersTrustedProxyIT {

    @LocalServerPort
    private int port;

    @Test
    void appliesForwardedForAndProtoFromPeerMatchingRouterPattern() throws Exception {
        HttpResponse<String> response =
                get(port, "/test-only/remote", "X-Forwarded-For", "203.0.113.9", "X-Forwarded-Proto", "https");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("203.0.113.9|https");
    }
}
