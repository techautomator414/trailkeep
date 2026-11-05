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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/**
 * The upload pipeline against a real clamd behind its TLS sidecar ({@link RealClamd}), as in the deployment
 * (ADR-0012). EICAR is assembled at run time, never committed.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
class ClamdClientIT {

    @TempDir
    Path tempDir;

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
        return intake(RealClamd.tlsPort());
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
