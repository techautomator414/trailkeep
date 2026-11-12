// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.ops;

import static io.trailkeep.support.HttpProbe.get;
import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@ActiveProfiles("test")
@AutoConfigureMetrics // Spring Boot tests switch metrics export off unless asked.
@Import({
    RemoteAddressController.class,
    TestOnlyEndpointsSecurity.class,
    ManagementEndpointsIT.ClusterIndicatorConfig.class,
    PostgresTestcontainersConfiguration.class
})
class ManagementEndpointsIT {

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
        // The real one, the SLA evaluator's last success in JOB_RUN (WP-21), is outside both groups too.
        assertThat(liveness.isMember("slaEvaluator")).isFalse();
        assertThat(readiness.isMember("slaEvaluator")).isFalse();
        assertThat(groups.getPrimary().isMember("slaEvaluator")).isTrue();
        // And the reminders tick's (WP-17b).
        assertThat(liveness.isMember("remindersTick")).isFalse();
        assertThat(readiness.isMember("remindersTick")).isFalse();
        assertThat(groups.getPrimary().isMember("remindersTick")).isTrue();
        // And the archive job's (WP-24).
        assertThat(liveness.isMember("archive")).isFalse();
        assertThat(readiness.isMember("archive")).isFalse();
        assertThat(groups.getPrimary().isMember("archive")).isTrue();
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

    /**
     * The alerting rules in {@code deploy/components/prometheus-rules} (WP-25) name only metrics the application or
     * its HikariCP pool serves, so a renamed gauge cannot leave a rule silently matching nothing; and every alert
     * carries a severity, a summary and a runbook link into {@code deploy/README.md}.
     */
    @Test
    void everyMetricTheAlertingRulesNameIsServedAndEveryAlertHasARunbook() throws Exception {
        String rules = Files.readString(Path.of("deploy/components/prometheus-rules/prometheus-rules.yaml"));
        String scrape = get(managementPort, "/actuator/prometheus").body();
        Set<String> names = new TreeSet<>();
        Matcher metric = Pattern.compile("\\b(?:trailkeep|hikaricp)_[a-z_]+").matcher(rules);
        while (metric.find()) {
            names.add(metric.group());
        }
        assertThat(names)
                .hasSizeGreaterThanOrEqualTo(8)
                .contains("hikaricp_connections_pending", "trailkeep_scanner_up");
        assertThat(names).allSatisfy(name -> assertThat(scrape).as(name).containsPattern("(?m)^" + name + "[{ ]"));

        int alerts = rules.split("- alert: ", -1).length - 1;
        assertThat(alerts).isGreaterThanOrEqualTo(9);
        assertThat(rules.split("severity: ", -1).length - 1).isEqualTo(alerts);
        assertThat(rules.split("summary: ", -1).length - 1).isEqualTo(alerts);
        Matcher runbook = Pattern.compile("runbook_url: \"[^\"]*/deploy/README\\.md#([a-z-]+)\"")
                .matcher(rules);
        String readme = Files.readString(Path.of("deploy/README.md"));
        int links = 0;
        while (runbook.find()) {
            links++;
            String anchor = runbook.group(1);
            assertThat(readme.lines()
                            .map(line -> line.toLowerCase(java.util.Locale.ROOT)
                                    .replaceAll("[^a-z0-9 -]", "")
                                    .strip()
                                    .replace(' ', '-')))
                    .as("deploy/README.md heading for #" + anchor)
                    .contains(anchor);
        }
        assertThat(links).isEqualTo(alerts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/loggers"})
    void otherActuatorEndpointsAreNotExposed(String path) throws Exception {
        assertThat(get(managementPort, path).statusCode()).isEqualTo(404);
    }

    @Test
    void prometheusIsNotServedOnTheApplicationPort() throws Exception {
        // Since PR 8 every application-port request needs an OIDC login first; behind it the path does not exist.
        HttpResponse<String> response = get(serverPort, "/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.body()).doesNotContain("jvm_memory_used_bytes");
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
