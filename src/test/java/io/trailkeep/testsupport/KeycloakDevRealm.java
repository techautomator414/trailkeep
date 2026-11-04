// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * A Keycloak container with the dev realm ({@code deploy/local/keycloak/trailkeep-dev-realm.json}) whose redirect URIs
 * name a fixed application port chosen up front, plus the properties that point the {@code idp} client at it. Used by
 * the login IT and the browser E2E tests with {@code webEnvironment = DEFINED_PORT}.
 */
public final class KeycloakDevRealm {

    /** Same image as compose.yaml. */
    public static final String IMAGE = "keycloak/keycloak:26.0.7";

    /** Realm name. */
    public static final String REALM = "trailkeep-dev";

    /** Every dev user's password (deliberately obvious; dev realm only). */
    public static final String PASSWORD = "dev-password";

    private final int appPort;
    private final GenericContainer<?> container;

    private KeycloakDevRealm(int appPort, GenericContainer<?> container) {
        this.appPort = appPort;
        this.container = container;
    }

    /**
     * Picks a free application port and starts Keycloak with the realm rewritten for it.
     *
     * @return the running realm
     */
    public static KeycloakDevRealm start() {
        int port = freePort();
        String realm;
        try {
            realm = Files.readString(Path.of("deploy/local/keycloak/trailkeep-dev-realm.json"))
                    .replace("http://localhost:8080", "http://localhost:" + port);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        GenericContainer<?> container = new GenericContainer<>(IMAGE)
                .withCommand("start-dev", "--import-realm")
                .withCopyToContainer(Transferable.of(realm), "/opt/keycloak/data/import/" + REALM + ".json")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                        .forPort(8080)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        container.start();
        return new KeycloakDevRealm(port, container);
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

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
