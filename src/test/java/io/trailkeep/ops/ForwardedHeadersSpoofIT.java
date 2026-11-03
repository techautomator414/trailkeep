// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.ops;

import static io.trailkeep.support.HttpProbe.get;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Part A #29 (amended r3): a client that is not the router cannot spoof its address. The test client connects from
 * 127.0.0.1, which is outside the test profile's placeholder router pattern and is not an RFC 1918 address either,
 * so this also proves Spring Boot's default internal-proxies list is not in effect for loopback peers.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@ActiveProfiles("test")
@Import({RemoteAddressController.class, PostgresTestcontainersConfiguration.class})
class ForwardedHeadersSpoofIT {

    @LocalServerPort
    private int port;

    @Test
    void ignoresForwardedForAndProtoFromPeerOutsideRouterPattern() throws Exception {
        HttpResponse<String> response =
                get(port, "/test-only/remote", "X-Forwarded-For", "203.0.113.9", "X-Forwarded-Proto", "https");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("127.0.0.1|http");
    }
}
