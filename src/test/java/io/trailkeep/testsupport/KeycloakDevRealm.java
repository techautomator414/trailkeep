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
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Keycloak dev realm ({@code deploy/local/keycloak/trailkeep-dev-realm.json}) shared by the login IT and the
 * browser E2E tests ({@code webEnvironment = DEFINED_PORT}): one container per test JVM, started on first use and
 * never owned by a test class or a Spring context (Testcontainers' Ryuk, or the shutdown hook where it is off, stops
 * it), so a build starts Keycloak once instead of once per class (issue 117: 24 starts of about 15 s each).
 *
 * <p>What differs per class is the application port: Keycloak cannot wildcard a port in the {@code trailkeep}
 * client's redirect URIs or its single back-channel logout URL. {@link #start()} therefore picks the class's port and
 * sets those values through the admin REST API (the pattern of {@link #logoutAtProvider(String)}), after ending every
 * session the previous class left, so each class starts with no one signed in. The realm's users and content are the
 * same for every class; a class that needs different realm content must not use this class. Classes run one after
 * another in one JVM, which the single logout URL requires: parallel execution would break back-channel logout.
 */
public final class KeycloakDevRealm {

    /** The same Keycloak version as compose.yaml (see images/keycloak/Dockerfile). */
    public static final DockerImageName IMAGE = TestImages.image("keycloak");

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

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Object LOCK = new Object();

    /** The one container of this JVM, started on first use. */
    private static @Nullable GenericContainer<?> shared;

    /** How many containers this JVM has started: 1 once any class used the realm (read by the evidence runs). */
    private static int starts;

    private final int appPort;
    private final GenericContainer<?> container;

    private KeycloakDevRealm(int appPort, GenericContainer<?> container) {
        this.appPort = appPort;
        this.container = container;
    }

    /**
     * Starts the JVM's Keycloak if no class did yet, ends the sessions of the previous class, picks a free
     * application port and points the {@code trailkeep} client's redirect URIs, web origins, post-logout URIs and
     * back-channel logout URL at it.
     *
     * @return the running realm, configured for this class's port
     */
    public static KeycloakDevRealm start() {
        synchronized (LOCK) {
            GenericContainer<?> container = shared();
            int port = freePort();
            KeycloakDevRealm realm = new KeycloakDevRealm(port, container);
            realm.admin(admin -> {
                admin.post("/admin/realms/" + REALM + "/logout-all");
                admin.configureClient(port);
            });
            return realm;
        }
    }

    private static GenericContainer<?> shared() {
        if (shared != null) {
            return shared;
        }
        String realm;
        try {
            realm = Files.readString(Path.of("deploy/local/keycloak/trailkeep-dev-realm.json"));
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
        starts++;
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop, "stop-keycloak"));
        shared = container;
        return container;
    }

    /**
     * How many Keycloak containers this JVM has started.
     *
     * @return 0 before any class used the realm, otherwise 1
     */
    public static int startsInThisJvm() {
        synchronized (LOCK) {
            return starts;
        }
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
        admin(admin -> admin.post("/admin/realms/" + REALM + "/users/" + userId + "/logout"));
    }

    /** The admin REST calls the realm makes, as the throwaway master-realm administrator. */
    private void admin(Consumer<Admin> calls) {
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
            calls.accept(new Admin(http, base, accessToken.group(1)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private record Admin(HttpClient http, String base, String token) {

        String get(String path) {
            return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
        }

        void post(String path) {
            send(HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.noBody()));
        }

        void put(String path, String json) {
            send(HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json)));
        }

        private String send(HttpRequest.Builder request) {
            try {
                HttpResponse<String> response = http.send(
                        request.header("Authorization", "Bearer " + token).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException(
                            "Keycloak admin call failed: " + response.statusCode() + " " + response.body());
                }
                return response.body();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        /** Points the {@code trailkeep} client at the application port of the class that is starting. */
        void configureClient(int port) {
            JsonNode clients = JSON.readTree(get("/admin/realms/" + REALM + "/clients?clientId=trailkeep"));
            String id = clients.get(0).get("id").asString();
            ObjectNode client = (ObjectNode) JSON.readTree(get("/admin/realms/" + REALM + "/clients/" + id));
            client.putArray("redirectUris").add("http://localhost:" + port + "/login/oauth2/code/idp");
            client.putArray("webOrigins").add("http://localhost:" + port);
            ObjectNode attributes = (ObjectNode) client.get("attributes");
            attributes.put("post.logout.redirect.uris", "http://localhost:" + port + "/*");
            attributes.put(
                    "backchannel.logout.url",
                    "http://host.docker.internal:" + port + "/logout/connect/back-channel/idp");
            put("/admin/realms/" + REALM + "/clients/" + id, JSON.writeValueAsString(client));
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
