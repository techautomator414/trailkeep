// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * A Keycloak container with the dev realm ({@code deploy/local/keycloak/trailkeep-dev-realm.json}) whose redirect URIs
 * name a fixed application port chosen up front, plus the properties that point the {@code idp} client at it. Used by
 * the login IT and the browser E2E tests with {@code webEnvironment = DEFINED_PORT}.
 *
 * <p>Test classes run one after another in one JVM and each starts its own realm, so {@link #start()} first stops the
 * realm of the class before it. Without that, every such class left a Keycloak container (about 0.9 GB each) running
 * until the JVM ended: 24 of them by the time the last integration test started ClamAV, which is more than a 16 GB CI
 * runner holds (the runner was shut down mid-build on #115). A cached application context of an earlier class is
 * never used again, because the context cache key includes that class's own property source.
 */
public final class KeycloakDevRealm {

    /** Same image as compose.yaml. */
    public static final String IMAGE = "keycloak/keycloak:26.0.7";

    /** Application ports are probed in [20000, 30000), below the ephemeral range Docker publishes into. */
    private static final int PORT_RANGE_START = 20_000;

    private static final int PORT_RANGE_END = 30_000;
    private static final int PORT_ATTEMPTS = 50;

    /** Realm name. */
    public static final String REALM = "trailkeep-dev";

    /** Throwaway administrator of the test container's master realm (never used outside tests). */
    private static final String ADMIN = "admin";

    private static final String ADMIN_PASSWORD = "admin-test-only";

    /** Every dev user's password (deliberately obvious; dev realm only). */
    public static final String PASSWORD = "dev-password";

    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");

    /** The realm of the class that ran last, stopped when the next one starts. */
    private static @Nullable KeycloakDevRealm last;

    private final int appPort;
    private final GenericContainer<?> container;

    private KeycloakDevRealm(int appPort, GenericContainer<?> container) {
        this.appPort = appPort;
        this.container = container;
    }

    /**
     * Stops the realm a previous test class started, picks a free application port and starts Keycloak with the realm
     * rewritten for it.
     *
     * @return the running realm
     */
    public static synchronized KeycloakDevRealm start() {
        if (last != null) {
            last.container.stop();
            last = null;
        }
        int port = freePort();
        String realm;
        try {
            realm = Files.readString(Path.of("deploy/local/keycloak/trailkeep-dev-realm.json"))
                    .replace("http://localhost:8080", "http://localhost:" + port)
                    .replace("http://host.docker.internal:8080", "http://host.docker.internal:" + port);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        GenericContainer<?> container = new GenericContainer<>(IMAGE)
                // Back-channel logout: Keycloak, inside Docker, calls the application on the host (as in compose).
                .withExtraHost("host.docker.internal", "host-gateway")
                .withLogConsumer(frame -> {
                    String line = frame.getUtf8String();
                    if (line.contains("WARN") || line.contains("ERROR")) {
                        System.out.print("[keycloak] " + line);
                    }
                })
                .withCommand("start-dev", "--import-realm")
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", ADMIN)
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
                .withCopyToContainer(Transferable.of(realm), "/opt/keycloak/data/import/" + REALM + ".json")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                        .forPort(8080)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        container.start();
        KeycloakDevRealm started = new KeycloakDevRealm(port, container);
        last = started;
        return started;
    }

    /**
     * The application port the realm's redirect URIs name.
     *
     * @return the port
     */
    public int appPort() {
        return appPort;
    }

    /**
     * An absolute application URL.
     *
     * @param path the path
     * @return the URL
     */
    public String app(String path) {
        return "http://localhost:" + appPort + path;
    }

    /**
     * The realm's issuer as the browser and the application both see it.
     *
     * @return the issuer URL
     */
    public String issuer() {
        return "http://localhost:" + container.getMappedPort(8080) + "/realms/" + REALM;
    }

    /**
     * Points the application at this realm, replacing the {@code test} profile's unreachable endpoints (explicit
     * endpoints win over discovery, so all of them are set).
     *
     * @param registry the dynamic property registry
     */
    public void register(DynamicPropertyRegistry registry) {
        String endpoints = issuer() + "/protocol/openid-connect";
        registry.add("server.port", () -> appPort);
        registry.add("management.server.port", () -> 0);
        registry.add("spring.security.oauth2.client.provider.idp.issuer-uri", this::issuer);
        registry.add("spring.security.oauth2.client.provider.idp.authorization-uri", () -> endpoints + "/auth");
        registry.add("spring.security.oauth2.client.provider.idp.token-uri", () -> endpoints + "/token");
        registry.add("spring.security.oauth2.client.provider.idp.jwk-set-uri", () -> endpoints + "/certs");
        registry.add("spring.security.oauth2.client.provider.idp.user-info-uri", () -> endpoints + "/userinfo");
    }

    /**
     * Logs a user out at the provider, as an administrator would. Keycloak then sends a back-channel logout token
     * to every client holding one of the user's sessions.
     *
     * @param userId the realm user's id (the {@code sub} claim)
     * @throws IOException on I/O failure
     * @throws InterruptedException if interrupted
     */
    public void logoutAtProvider(String userId) throws IOException, InterruptedException {
        String base = "http://localhost:" + container.getMappedPort(8080);
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> token = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/realms/master/protocol/openid-connect/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString("grant_type=password&client_id=admin-cli"
                                    + "&username=" + ADMIN + "&password=" + ADMIN_PASSWORD))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            Matcher accessToken = ACCESS_TOKEN.matcher(token.body());
            if (!accessToken.find()) {
                throw new IllegalStateException("No admin token: " + token.statusCode());
            }
            HttpResponse<String> logout = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/admin/realms/" + REALM + "/users/" + userId + "/logout"))
                            .header("Authorization", "Bearer " + accessToken.group(1))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (logout.statusCode() / 100 != 2) {
                throw new IllegalStateException("Provider logout failed: " + logout.statusCode() + " " + logout.body());
            }
        }
    }

    /**
     * A port the application binds minutes after this call, once Keycloak and the database containers are up. The
     * kernel's ephemeral range (32768 to 60999 by default on Linux) is where Docker publishes container ports and
     * where outgoing connections take their source ports, so a port from
     * {@code new ServerSocket(0)} can be taken by the next Testcontainers start before the context binds it (seen as
     * {@code PortInUseException} in {@code SharedSessionsIT}). Probing below that range avoids the race.
     */
    private static int freePort() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < PORT_ATTEMPTS; attempt++) {
            int candidate = random.nextInt(PORT_RANGE_START, PORT_RANGE_END);
            if (isFree(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No free application port found in " + PORT_RANGE_START + "-" + PORT_RANGE_END);
    }

    private static boolean isFree(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            return socket.isBound(); // bound and released; a bind to an explicit port either gets it or throws
        } catch (IOException e) {
            return false;
        }
    }
}
