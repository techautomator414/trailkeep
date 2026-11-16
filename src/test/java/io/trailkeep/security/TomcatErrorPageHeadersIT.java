// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * A request Tomcat refuses before Spring's filter chain runs gets Tomcat's own error page, and that page carries the
 * security headers too (WP-28 part e). The first DAST runs found it without any (ZAP rule 10038) on a URL with an
 * encoded backslash; the second path below is the one ZAP sent.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class TomcatErrorPageHeadersIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @LocalServerPort
    private int port;

    @ParameterizedTest
    @ValueSource(strings = {"/%5C", "/*-/*%60/*%5C%60/*"})
    void tomcatsOwnErrorPageCarriesTheSecurityHeaders(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = get(path);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Security-Policy")).hasValue(TomcatErrorPageHeaders.POLICY);
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("X-Frame-Options")).hasValue("DENY");
        assertThat(response.headers().firstValue("Referrer-Policy")).hasValue("same-origin");
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        // Still Tomcat's page, as Spring Boot configures it: no server version, no report.
        assertThat(response.body()).contains("400").doesNotContain("Apache Tomcat/");
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
