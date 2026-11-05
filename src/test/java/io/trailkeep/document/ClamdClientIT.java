// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.testsupport.RequiresDocker;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The upload pipeline against a real clamd (the official ClamAV image, the same tag as {@code compose.yaml}) behind a
 * ghostunnel TLS sidecar sharing its network namespace, as in the deployment (ADR-0012): clamd's plaintext port is
 * never published, only the sidecar's. The sidecar's certificate is issued at run time by {@link TestTls}. The image
 * ships with signature databases, so freshclam is switched off: the test needs no network beyond the image pulls, and
 * EICAR is in every database. EICAR itself is assembled at run time, never committed.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
class ClamdClientIT {

    static final DockerImageName IMAGE = DockerImageName.parse("clamav/clamav:1.4.3");

    static final DockerImageName SIDECAR = DockerImageName.parse("ghostunnel/ghostunnel:v1.8.4");

    private static final int TLS_PORT = 3311;

    private static final GenericContainer<?> CLAMD = new GenericContainer<>(IMAGE)
            .withEnv("CLAMAV_NO_FRESHCLAMD", "true")
            // The sidecar listens in this container's network namespace, so its port is published here.
            .withExposedPorts(TLS_PORT)
            .waitingFor(
                    Wait.forLogMessage(".*socket found, clamd started.*", 1).withStartupTimeout(Duration.ofMinutes(3)));

    private static @Nullable GenericContainer<?> tunnel;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void start() {
        CLAMD.start();
        Path chain = TestTls.shared().writePem();
        GenericContainer<?> sidecar = new GenericContainer<>(SIDECAR)
                .withNetworkMode("container:" + CLAMD.getContainerId())
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
                .waitingFor(Wait.forLogMessage(".*listening.*", 1).withStartupTimeout(Duration.ofMinutes(1)));
        sidecar.start();
        tunnel = sidecar;
    }

    @AfterAll
    static void stop() {
        if (tunnel != null) {
            tunnel.stop();
        }
        CLAMD.stop();
    }

    private UploadIntake intake(int port) {
        AttachmentProperties properties = new AttachmentProperties(
                tempDir,
                DataSize.ofMegabytes(25),
                25_000_000,
                ScanMode.CLAMD,
                new AttachmentProperties.Clamd(
                        "localhost",
                        port,
                        "clamd",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(1)));
        return new UploadIntake(
                properties,
                Optional.of(new ClamdClient(properties, TestTls.shared().bundles())));
    }

    private UploadIntake intake() {
        return intake(CLAMD.getMappedPort(TLS_PORT));
    }

    @Test
    void eicarIsQuarantinedWithTheSignatureClamdReports() throws IOException {
        try (ScannedUpload upload = intake().receive("eicar.txt", new ByteArrayInputStream(UploadSamples.eicar()))) {
            assertThat(upload.verdict().status()).isEqualTo(ScanStatus.QUARANTINED);
            assertThat(upload.verdict().signature()).containsIgnoringCase("eicar");
            assertThat(upload.contentType()).isEqualTo("text/plain");
        }
    }

    @Test
    void cleanFileOfManyChunksIsClean() throws IOException {
        byte[] content = UploadSamples.ascii("a,b\n".repeat(1_000_000)); // 4 MB, 62 chunks
        try (ScannedUpload upload = intake().receive("data.csv", new ByteArrayInputStream(content))) {
            assertThat(upload.verdict()).isEqualTo(ScanVerdict.clean());
            assertThat(upload.sizeBytes()).isEqualTo(content.length);
        }
    }

    @Test
    void cleanPdfIsClean() throws IOException {
        try (ScannedUpload upload = intake().receive("report.pdf", new ByteArrayInputStream(UploadSamples.pdf()))) {
            assertThat(upload.verdict()).isEqualTo(ScanVerdict.clean());
        }
    }

    @Test
    void closedPortRefusesTheUpload() throws IOException {
        assertThatThrownBy(() -> intake(ClamdClientTest.closedPort())
                        .receive("notes.txt", new ByteArrayInputStream(UploadSamples.ascii("abc"))))
                .isInstanceOf(ScannerUnavailableException.class);
    }
}
