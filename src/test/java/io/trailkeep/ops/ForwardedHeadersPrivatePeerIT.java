// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.DockerImageName;

/**
 * Part A #29 (amended r3) and WP-28's acceptance: a client on a <em>private</em> address that is not the router
 * cannot spoof its address or scheme. {@link ForwardedHeadersSpoofIT} connects from loopback, which is not an RFC 1918
 * address; here a container on Docker's bridge network (172.16.0.0/12 by default) calls the application through the
 * host gateway, so the application's peer is a site-local address outside the router pattern. Spring Boot's default
 * {@code internal-proxies} trusts exactly such peers, so this fails if that default were ever back in force.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@ActiveProfiles("test")
@Import({RemoteAddressController.class, TestOnlyEndpointsSecurity.class, PostgresTestcontainersConfiguration.class})
class ForwardedHeadersPrivatePeerIT {

    /** A small public curl image, used only as a client on the bridge network. */
    static final DockerImageName CURL = DockerImageName.parse("curlimages/curl:8.17.0");

    /** The test profile's placeholder router pattern ({@code app.proxy.pattern}). */
    private static final String ROUTER = "10\\.128\\.\\d+\\.\\d+";

    @LocalServerPort
    private int port;

    @Test
    void ignoresForwardedForAndProtoFromAPrivatePeerOutsideTheRouterPattern() throws Exception {
        String seen = fromTheBridge("-H", "X-Forwarded-For: 203.0.113.9", "-H", "X-Forwarded-Proto: https");

        String[] parts = seen.split("\\|", -1);
        assertThat(parts).as("address|scheme, got %s", seen).hasSize(2);
        InetAddress peer = InetAddress.getByName(parts[0]);
        assertThat(peer.isSiteLocalAddress())
                .as("%s is a private address", parts[0])
                .isTrue();
        assertThat(parts[0]).doesNotMatch(ROUTER).isNotEqualTo("203.0.113.9");
        assertThat(parts[1]).isEqualTo("http");
    }

    private String fromTheBridge(String... headers) {
        List<String> command = new ArrayList<>(List.of("-sS", "--max-time", "20"));
        command.addAll(List.of(headers));
        command.add("http://host.docker.internal:" + port + "/test-only/remote");
        try (GenericContainer<?> curl = new GenericContainer<>(CURL)
                .withExtraHost("host.docker.internal", "host-gateway")
                .withCommand(command.toArray(String[]::new))
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(60)))) {
            curl.start();
            return curl.getLogs(OutputFrame.OutputType.STDOUT).strip();
        }
    }
}
