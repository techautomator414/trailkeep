// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import java.nio.file.Path;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * A real clamd (the official ClamAV image, the same tag as {@code compose.yaml}) behind a ghostunnel TLS sidecar
 * sharing its network namespace, as in the deployment (ADR-0012): clamd's plaintext port is never published, only
 * the sidecar's. The sidecar's certificate is {@link TestTls#shared()}. The image ships with signature databases, so
 * freshclam is switched off: no network beyond the image pulls, and EICAR is in every database. Started once per test
 * JVM, on first use, and shared by every test that needs it; Testcontainers removes it when the JVM ends.
 */
final class RealClamd {

    static final DockerImageName IMAGE = DockerImageName.parse("clamav/clamav:1.4.3");

    static final DockerImageName SIDECAR = DockerImageName.parse("ghostunnel/ghostunnel:v1.8.4");

    private static final int TLS_PORT = 3311;

    private static @Nullable GenericContainer<?> clamd;

    private RealClamd() {}

    /**
     * Starts clamd and its sidecar unless they run already.
     *
     * @return the sidecar's TLS port on {@code localhost}
     */
    static synchronized int tlsPort() {
        GenericContainer<?> running = clamd;
        if (running == null) {
            running = new GenericContainer<>(IMAGE)
                    .withEnv("CLAMAV_NO_FRESHCLAMD", "true")
                    // The sidecar listens in this container's network namespace, so its port is published here.
                    .withExposedPorts(TLS_PORT)
                    .waitingFor(Wait.forLogMessage(".*socket found, clamd started.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(3)));
            running.start();
            Path chain = TestTls.shared().writePem();
            new GenericContainer<>(SIDECAR)
                    .withNetworkMode("container:" + running.getContainerId())
                    .withCopyFileToContainer(MountableFile.forHostPath(chain, 0444), "/tls/chain.pem")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(chain.resolveSibling("server-key.pem"), 0444), "/tls/key.pem")
                    .withCommand(
                            "server",
                            "--listen=0.0.0.0:" + TLS_PORT,
                            "--target=localhost:3310",
                            "--cert=/tls/chain.pem",
                            "--key=/tls/key.pem",
                            "--disable-authentication")
                    .waitingFor(Wait.forLogMessage(".*listening.*", 1).withStartupTimeout(Duration.ofMinutes(1)))
                    .start();
            clamd = running;
        }
        return running.getMappedPort(TLS_PORT);
    }
}
