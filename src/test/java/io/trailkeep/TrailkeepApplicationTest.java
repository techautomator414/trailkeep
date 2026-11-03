// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@ActiveProfiles("test")
class TrailkeepApplicationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private ApplicationContext context;

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.getId()).startsWith("trailkeep");
    }

    @Test
    void healthIsUpOnTheManagementPort() throws IOException, InterruptedException {
        assertThat(managementPort).isPositive().isNotEqualTo(serverPort);

        HttpResponse<String> response = get(managementPort, "/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void healthIsNotServedOnTheApplicationPort() throws IOException, InterruptedException {
        HttpResponse<String> response = get(serverPort, "/actuator/health");

        assertThat(response.statusCode()).isEqualTo(404);
    }

    private static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
