// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.testsupport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Nu Html Checker (vnu), which validates the HTML every accessibility test checks with axe (BUILD_PLAN §10 and
 * §12: "HTML validity (Nu validator)" on every page and major UI state; IMPLEMENTATION_PLAN Part G, every PR). It runs
 * as the project's official image in one container per test JVM, started on first use and owned by no test class (the
 * pattern of {@link KeycloakDevRealm}); the JVM posts each document to its HTTP service. The checker is a tool the
 * tests run, not a dependency of the build: the {@code nu.validator:validator} library would bring log4j 1.2, which
 * the enforcer bans, and licences the allow-list does not hold.
 *
 * <p>Errors gate (0 errors); warnings are printed and do not. No message is filtered.
 */
public final class NuHtmlChecker {

    /**
     * The Nu Html Checker 26.11.14 (git revision c1619cc379312770dc018376ca032771d1e39ad1), built 2026-11-14 and
     * published as {@code ghcr.io/validator/validator:latest}; it labels no version, so it is pinned by digest. To
     * bump it: {@code docker pull ghcr.io/validator/validator:latest}, read the version with {@code docker run --rm
     * <image> java -jar /vnu.jar --version}, then replace the digest, the version, the revision and the date here.
     */
    public static final String IMAGE =
            "ghcr.io/validator/validator@sha256:" + "ab58f1a04ce22a393725e3fd6468d2763c08be6a9dc6fcaa0b1d93dd0c5f804e";

    private static final int PORT = 8888;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Object LOCK = new Object();

    /** The one container of this JVM, started on first use. */
    private static @Nullable GenericContainer<?> shared;

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    private NuHtmlChecker() {}

    /**
     * One message of the checker.
     *
     * @param error whether it gates: an error, or a document the checker could not read
     * @param line the last line of the extract, or 0
     * @param text the message
     * @param extract the markup around it
     */
    public record Message(boolean error, int line, String text, String extract) {

        /**
         * The message on one line, for an assertion or the test output.
         *
         * @param label what was validated
         * @return the line
         */
        public String describe(String label) {
            return label + ", line " + line + ": " + text + " ["
                    + extract.replaceAll("\\s+", " ").strip() + "]";
        }
    }

    /**
     * Validates a complete HTML document.
     *
     * @param html the document
     * @return the checker's messages, errors and warnings
     */
    public static List<Message> check(String html) {
        URI uri =
                URI.create("http://" + container().getHost() + ":" + container().getMappedPort(PORT) + "/?out=json");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Content-Type", "text/html; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(html, StandardCharsets.UTF_8))
                .build();
        String body;
        try {
            body = HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException e) {
            throw new IllegalStateException("the Nu Html Checker did not answer", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while validating", e);
        }
        List<Message> messages = new ArrayList<>();
        for (JsonNode message : JSON.readTree(body).path("messages")) {
            String type = message.path("type").asString();
            messages.add(new Message(
                    "error".equals(type) || "non-document-error".equals(type),
                    message.path("lastLine").asInt(0),
                    message.path("message").asString(),
                    message.path("extract").asString()));
        }
        return messages;
    }

    /**
     * Wraps an htmx fragment in the smallest valid document, so that its markup is validated without errors for the
     * missing document structure.
     *
     * @param fragment the fragment's markup
     * @return the document
     */
    public static String wrapFragment(String fragment) {
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>htmx fragment</title></head>"
                + "<body>\n" + fragment + "\n</body></html>";
    }

    private static GenericContainer<?> container() {
        synchronized (LOCK) {
            if (shared == null) {
                GenericContainer<?> container = new GenericContainer<>(IMAGE)
                        .withExposedPorts(PORT)
                        .waitingFor(Wait.forHttp("/").forPort(PORT).withStartupTimeout(Duration.ofMinutes(2)));
                container.start();
                Runtime.getRuntime().addShutdownHook(new Thread(container::stop, "stop-nu-html-checker"));
                shared = container;
            }
            return shared;
        }
    }
}
