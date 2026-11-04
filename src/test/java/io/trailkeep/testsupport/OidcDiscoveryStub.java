// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.testsupport;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * A loopback OpenID Provider discovery document, for tests that boot the {@code prod} profile (which finds its
 * provider by issuer discovery at startup) but never log in. Only {@code /.well-known/openid-configuration} is
 * served; every endpoint it names points back at this stub and answers 404.
 */
public final class OidcDiscoveryStub implements AutoCloseable {

    private final HttpServer server;
    private final String issuer;

    private OidcDiscoveryStub(HttpServer server) {
        this.server = server;
        this.issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/stub";
    }

    /**
     * Starts the stub on a free loopback port.
     *
     * @return the running stub
     */
    public static OidcDiscoveryStub start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            OidcDiscoveryStub stub = new OidcDiscoveryStub(server);
            server.createContext("/realms/stub/.well-known/openid-configuration", exchange -> {
                byte[] body = stub.document().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The issuer to configure as {@code app.security.oidc.issuer-uri}.
     *
     * @return the issuer URL
     */
    public String issuer() {
        return issuer;
    }

    private String document() {
        String base = issuer + "/protocol/openid-connect";
        return """
                {"issuer":"%1$s","authorization_endpoint":"%2$s/auth","token_endpoint":"%2$s/token",
                 "jwks_uri":"%2$s/certs","userinfo_endpoint":"%2$s/userinfo","response_types_supported":["code"],
                 "subject_types_supported":["public"],"id_token_signing_alg_values_supported":["RS256"],
                 "scopes_supported":["openid","profile","email"]}""".formatted(issuer, base);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
