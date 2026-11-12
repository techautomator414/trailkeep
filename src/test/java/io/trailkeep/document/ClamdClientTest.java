// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.util.unit.DataSize;

/** {@link ClamdClient} against an in-process clamd: the framing, both verdicts, and every way it fails closed. */
class ClamdClientTest {

    @TempDir
    Path dir;

    static ClamdClient client(String host, int port, Duration readTimeout, Duration scanTimeout, SslBundles trust) {
        return new ClamdClient(
                new AttachmentProperties(
                        Path.of("unused"),
                        DataSize.ofMegabytes(25),
                        25_000_000,
                        ScanMode.CLAMD,
                        new AttachmentProperties.Clamd(
                                host, port, "clamd", Duration.ofSeconds(2), readTimeout, scanTimeout),
                        8),
                trust);
    }

    static ClamdClient client(int port, Duration readTimeout, Duration scanTimeout) {
        return client(
                "localhost", port, readTimeout, scanTimeout, TestTls.shared().bundles());
    }

    static ClamdClient client(int port) {
        return client(port, Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    /**
     * A loopback port nothing listens on.
     *
     * @return the port
     */
    static int closedPort() throws IOException {
        try (ServerSocket closed = TestTls.shared().serverSocket()) {
            return closed.getLocalPort();
        }
    }

    private Path file(byte[] content) throws IOException {
        return Files.write(dir.resolve("scan.bin"), content);
    }

    @Test
    void okReplyIsCleanAndTheFileIsStreamedInSizePrefixedChunks() throws IOException {
        byte[] content = new byte[ClamdClient.CHUNK_SIZE * 2 + 17];
        new Random(42).nextBytes(content);
        try (FakeClamd clamd = FakeClamd.answering(bytes -> "stream: OK")) {
            ScanVerdict verdict = client(clamd.port()).scan(file(content));

            assertThat(verdict).isEqualTo(ScanVerdict.clean());
            assertThat(clamd.commands).containsExactly("zINSTREAM");
            assertThat(clamd.chunkLengths).containsExactly(ClamdClient.CHUNK_SIZE, ClamdClient.CHUNK_SIZE, 17);
            assertThat(clamd.received.getFirst()).isEqualTo(content);
        }
    }

    @Test
    void emptyFileSendsOnlyTheTerminatingZeroLength() throws IOException {
        try (FakeClamd clamd = FakeClamd.answering(bytes -> "stream: OK")) {
            assertThat(client(clamd.port()).scan(file(new byte[0]))).isEqualTo(ScanVerdict.clean());
            assertThat(clamd.chunkLengths).isEmpty();
        }
    }

    @Test
    void foundReplyQuarantinesWithTheSignatureName() throws IOException {
        try (FakeClamd clamd = FakeClamd.answering(bytes -> "stream: Win.Test.EICAR_HDB-1 FOUND")) {
            ScanVerdict verdict = client(clamd.port()).scan(file("x".getBytes(StandardCharsets.US_ASCII)));

            assertThat(verdict.status()).isEqualTo(ScanStatus.QUARANTINED);
            assertThat(verdict.signature()).isEqualTo("Win.Test.EICAR_HDB-1");
        }
    }

    @Test
    void overlongSignatureIsKeptToTheStoredWidth() throws IOException {
        String signature = "S".repeat(300);
        try (FakeClamd clamd = FakeClamd.answering(bytes -> "stream: " + signature + " FOUND")) {
            ScanVerdict verdict = client(clamd.port()).scan(file(new byte[1]));

            assertThat(verdict.signature()).hasSize(ScanVerdict.MAX_SIGNATURE);
        }
    }

    @Test
    void errorReplyMeansTheScannerIsUnavailable() throws IOException {
        try (FakeClamd clamd = FakeClamd.answering(bytes -> "INSTREAM size limit exceeded. ERROR")) {
            Path file = file(new byte[10]);
            assertThatThrownBy(() -> client(clamd.port()).scan(file)).isInstanceOf(ScannerUnavailableException.class);
        }
    }

    /** R13: clamd's stream-limit reply is told apart, so the importer can report the file and go on (WP-26 part g). */
    @Test
    void theDigestHashesTheVeryBytesSentToClamd() throws IOException, NoSuchAlgorithmException {
        byte[] content = new byte[ClamdClient.CHUNK_SIZE + 5];
        new Random(7).nextBytes(content);
        MessageDigest sent = MessageDigest.getInstance("SHA-256");
        try (FakeClamd clamd = FakeClamd.answering(bytes -> "stream: OK")) {
            assertThat(client(clamd.port()).scan(file(content), sent)).isEqualTo(ScanVerdict.clean());

            byte[] received = clamd.received.getFirst();
            assertThat(sent.digest())
                    .isEqualTo(MessageDigest.getInstance("SHA-256").digest(received));
            assertThat(received).isEqualTo(content);
        }
    }

    @Test
    void streamLimitRepliesAreSizeRefusalsThatStillFailClosed() throws IOException {
        try (FakeClamd clamd = FakeClamd.answering(bytes -> "INSTREAM size limit exceeded. ERROR")) {
            Path file = file(new byte[10]);
            assertThatThrownBy(() -> client(clamd.port()).scan(file))
                    .isInstanceOf(ScanSizeExceededException.class)
                    .isInstanceOf(ScannerUnavailableException.class);
        }
        assertThatThrownBy(() -> ClamdClient.verdict("Some other thing. ERROR"))
                .isInstanceOf(ScannerUnavailableException.class)
                .isNotInstanceOf(ScanSizeExceededException.class);
    }

    @Test
    void anyOtherReplyMeansTheScannerIsUnavailable() {
        for (String reply :
                Arrays.asList("UNKNOWN COMMAND", "stream: ", "stream: OK please", "stream:  FOUND", "OK", "")) {
            assertThatThrownBy(() -> ClamdClient.verdict(reply))
                    .as(reply)
                    .isInstanceOf(ScannerUnavailableException.class);
        }
    }

    @Test
    void unterminatedReplyMeansTheScannerIsUnavailable() throws IOException {
        try (FakeClamd clamd = FakeClamd.rawReply("stream: OK".getBytes(StandardCharsets.US_ASCII))) {
            Path file = file(new byte[10]);
            assertThatThrownBy(() -> client(clamd.port()).scan(file)).isInstanceOf(ScannerUnavailableException.class);
        }
    }

    @Test
    void endlessReplyIsCutOffAndMeansTheScannerIsUnavailable() throws IOException {
        byte[] flood = new byte[4096];
        Arrays.fill(flood, (byte) 'A');
        try (FakeClamd clamd = FakeClamd.rawReply(flood)) {
            Path file = file(new byte[10]);
            assertThatThrownBy(() -> client(clamd.port()).scan(file)).isInstanceOf(ScannerUnavailableException.class);
        }
    }

    @Test
    void noReplyWithinTheReadTimeoutMeansTheScannerIsUnavailable() throws IOException {
        try (FakeClamd clamd = FakeClamd.answering(bytes -> null)) {
            Path file = file(new byte[10]);
            long start = System.nanoTime();
            assertThatThrownBy(() -> client(clamd.port(), Duration.ofMillis(300), Duration.ofSeconds(10))
                            .scan(file))
                    .isInstanceOf(ScannerUnavailableException.class)
                    .hasCauseInstanceOf(SocketTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test
    void theWholeExchangeIsBoundedByTheScanTimeout() throws IOException {
        try (FakeClamd clamd = FakeClamd.answering(bytes -> null)) {
            Path file = file(new byte[10]);
            long start = System.nanoTime();
            assertThatThrownBy(() -> client(clamd.port(), Duration.ofSeconds(30), Duration.ofMillis(300))
                            .scan(file))
                    .isInstanceOf(ScannerUnavailableException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test
    void refusedConnectionMeansTheScannerIsUnavailable() throws IOException {
        int port = closedPort();
        Path file = file(new byte[10]);
        assertThatThrownBy(() -> client(port).scan(file)).isInstanceOf(ScannerUnavailableException.class);
    }

    @Test
    void certificateFromAnotherAuthorityFailsTheHandshakeAndMeansTheScannerIsUnavailable() throws IOException {
        try (FakeClamd impostor = FakeClamd.answering(TestTls.issue("dns:localhost"), bytes -> "stream: OK")) {
            Path file = file(new byte[10]);
            assertThatThrownBy(() -> client(impostor.port()).scan(file))
                    .isInstanceOf(ScannerUnavailableException.class)
                    .hasCauseInstanceOf(SSLException.class);
            assertThat(impostor.received).isEmpty();
        }
    }

    @Test
    void certificateNamingAnotherHostFailsTheHandshakeAndMeansTheScannerIsUnavailable() throws IOException {
        // A trusted authority, but the certificate names another host than the one connected to.
        TestTls misnamed = TestTls.issue("dns:clamd.invalid");
        try (FakeClamd clamd = FakeClamd.answering(misnamed, bytes -> "stream: OK")) {
            Path file = file(new byte[10]);
            ClamdClient client = client(
                    "localhost", clamd.port(), Duration.ofSeconds(5), Duration.ofSeconds(10), misnamed.bundles());
            assertThatThrownBy(() -> client.scan(file))
                    .isInstanceOf(ScannerUnavailableException.class)
                    .hasCauseInstanceOf(SSLException.class);
            assertThat(clamd.received).isEmpty();
        }
    }

    @Test
    void missingHostFailsConstructionEvenIfValidationWereBypassed() {
        Duration second = Duration.ofSeconds(1);
        assertThatThrownBy(() -> client(" ", 9, second, second, TestTls.shared().bundles()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.attachments.clamd.host");
    }

    @Test
    void onlyTls12AndAboveAreOffered() throws IOException {
        try (FakeClamd clamd = FakeClamd.answering(bytes -> "stream: OK")) {
            assertThat(client(clamd.port()).scan(file(new byte[1]))).isEqualTo(ScanVerdict.clean());
            assertThat(clamd.protocols).allMatch(p -> p.equals("TLSv1.3") || p.equals("TLSv1.2"));
        }
    }

    @Test
    void verdictIsCleanOrQuarantinedWithSignature() {
        assertThatThrownBy(() -> new ScanVerdict(ScanStatus.RELEASED, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScanVerdict(ScanStatus.QUARANTINED, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScanVerdict(ScanStatus.CLEAN, "Sig")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScanVerdict(ScanStatus.NOT_SCANNED, "Sig"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ScanVerdict.notScanned().status()).isEqualTo(ScanStatus.NOT_SCANNED);
        assertThat(ScanVerdict.notScanned().signature()).isNull();
        assertThatThrownBy(() -> ScanVerdict.found(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScanVerdict.found("S".repeat(201))).isInstanceOf(IllegalArgumentException.class);
    }
}
