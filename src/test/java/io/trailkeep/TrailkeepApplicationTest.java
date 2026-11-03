/*
 * SPDX-License-Identifier: Apache-2.0
 */
package io.trailkeep;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0"})
class TrailkeepApplicationTest {

    @Autowired
    private Environment environment;

    @Test
    void healthIsUpOnTheManagementPort() throws Exception {
        String managementPort = environment.getRequiredProperty("local.management.port");
        String serverPort = environment.getRequiredProperty("local.server.port");

        HttpResponse<String> health = get(managementPort, "/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");

        assertThat(managementPort).isNotEqualTo(serverPort);
        assertThat(get(serverPort, "/actuator/health").statusCode()).isEqualTo(404);
    }

    private static HttpResponse<String> get(String port, String path) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
