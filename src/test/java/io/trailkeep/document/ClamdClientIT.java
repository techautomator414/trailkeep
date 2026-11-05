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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * The upload pipeline against a real clamd (the official ClamAV image, the same tag as {@code compose.yaml}). The
 * image ships with signature databases, so freshclam is switched off: the test needs no network beyond the image
 * pull, and EICAR is in every database. EICAR itself is assembled at run time, never committed.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
class ClamdClientIT {

    static final DockerImageName IMAGE = DockerImageName.parse("clamav/clamav:1.4.3");

    private static final GenericContainer<?> CLAMD = new GenericContainer<>(IMAGE)
            .withEnv("CLAMAV_NO_FRESHCLAMD", "true")
            .withExposedPorts(3310)
            .waitingFor(
                    Wait.forLogMessage(".*socket found, clamd started.*", 1).withStartupTimeout(Duration.ofMinutes(3)));

    @TempDir
    Path tempDir;

    @BeforeAll
    static void start() {
        CLAMD.start();
    }

    @AfterAll
    static void stop() {
        CLAMD.stop();
    }

    private UploadIntake intake(int port) {
        AttachmentProperties properties = new AttachmentProperties(
                tempDir,
                DataSize.ofMegabytes(25),
                25_000_000,
                new AttachmentProperties.Clamd(
                        CLAMD.getHost(), port, Duration.ofSeconds(2), Duration.ofSeconds(30), Duration.ofMinutes(1)));
        return new UploadIntake(properties, new ClamdClient(properties));
    }

    private UploadIntake intake() {
        return intake(CLAMD.getMappedPort(3310));
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
    void closedPortRefusesTheUpload() {
        assertThatThrownBy(() -> intake(1).receive("notes.txt", new ByteArrayInputStream(UploadSamples.ascii("abc"))))
                .isInstanceOf(ScannerUnavailableException.class);
    }
}
