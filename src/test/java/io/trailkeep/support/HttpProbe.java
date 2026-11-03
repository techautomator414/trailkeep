// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Minimal real-socket HTTP client for tests that need Tomcat's connector (forwarded headers, ports). */
public final class HttpProbe {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private HttpProbe() {}

    /**
     * Sends a GET to {@code 127.0.0.1}.
     *
     * @param port the local port
     * @param path the request path
     * @param headers alternating header names and values
     * @return the response with a string body
     * @throws IOException on I/O failure
     * @throws InterruptedException if interrupted while waiting
     */
    public static HttpResponse<String> get(int port, String path, String... headers)
            throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(TIMEOUT)
                    .GET();
            if (headers.length > 0) {
                request.headers(headers);
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
