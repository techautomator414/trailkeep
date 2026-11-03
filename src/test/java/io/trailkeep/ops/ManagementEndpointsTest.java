// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.ops;

import static io.trailkeep.support.HttpProbe.get;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Management port contract: probe groups, Prometheus, nothing else exposed, request id echoed. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@ActiveProfiles("test")
@AutoConfigureMetrics // Spring Boot tests switch metrics export off unless asked.
@Import({RemoteAddressController.class, ManagementEndpointsTest.ClusterIndicatorConfig.class})
class ManagementEndpointsTest {

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private HealthEndpointGroups groups;

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
    void probeGroupsAreUpOnTheManagementPort(String path) throws Exception {
        HttpResponse<String> response = get(managementPort, path);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void clusterLevelIndicatorIsExcludedFromBothProbeGroupsByConstruction() throws Exception {
        HealthEndpointGroup liveness = Objects.requireNonNull(groups.get("liveness"));
        HealthEndpointGroup readiness = Objects.requireNonNull(groups.get("readiness"));
        assertThat(liveness.isMember("clusterJob")).isFalse();
        assertThat(readiness.isMember("clusterJob")).isFalse();
        assertThat(liveness.isMember("livenessState")).isTrue();
        assertThat(readiness.isMember("readinessState")).isTrue();

        // The failing cluster indicator takes the aggregate down but leaves both probes UP.
        assertThat(get(managementPort, "/actuator/health").statusCode()).isEqualTo(503);
        assertThat(get(managementPort, "/actuator/health/liveness").statusCode())
                .isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode())
                .isEqualTo(200);
    }

    @Test
    void prometheusIsScrapedFromTheManagementPort() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("jvm_memory_used_bytes");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/loggers"})
    void otherActuatorEndpointsAreNotExposed(String path) throws Exception {
        assertThat(get(managementPort, path).statusCode()).isEqualTo(404);
    }

    @Test
    void prometheusIsNotServedOnTheApplicationPort() throws Exception {
        assertThat(get(serverPort, "/actuator/prometheus").statusCode()).isEqualTo(404);
    }

    @Test
    void theApplicationPortEchoesTheRequestId() throws Exception {
        HttpResponse<String> response = get(serverPort, "/test-only/remote", "X-Request-Id", "abc-123");

        assertThat(response.headers().firstValue("X-Request-Id")).hasValue("abc-123");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClusterIndicatorConfig {

        /** Stands in for a future job last-run indicator: down for the whole cluster. */
        @Bean
        HealthIndicator clusterJobHealthIndicator() {
            return () -> Health.down()
                    .withDetail("reason", "simulated stale job run")
                    .build();
        }
    }
}
