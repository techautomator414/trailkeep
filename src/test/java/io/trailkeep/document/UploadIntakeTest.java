// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.document.AttachmentRejectedException.Reason;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/** The intake pipeline: hash and size while copying, then type, then scan, and the temp file gone on every failure. */
class UploadIntakeTest {

    private static final int CAP = 1024 * 1024;

    @TempDir
    Path tempDir;

    private final AtomicReference<Path> scanned = new AtomicReference<>();

    private UploadIntake intake(VirusScanner scanner) {
        return new UploadIntake(
                new AttachmentProperties(
                        tempDir,
                        DataSize.ofBytes(CAP),
                        25_000_000,
                        new AttachmentProperties.Clamd(
                                "127.0.0.1", 9, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1))),
                scanner);
    }

    private UploadIntake clean() {
        return intake(file -> {
            scanned.set(file);
            return ScanVerdict.clean();
        });
    }

    private long tempFiles() throws IOException {
        try (Stream<Path> files = Files.list(tempDir)) {
            return files.count();
        }
    }

    private static InputStream bytes(byte[] content) {
        return new ByteArrayInputStream(content);
    }

    @Test
    void validUploadIsHashedSizedTypedAndScannedInTempFileOwnedByItsResult() throws IOException {
        ScannedUpload upload = clean().receive("notes.txt", bytes(UploadSamples.ascii("abc")));
        try (upload) {
            assertThat(upload.contentType()).isEqualTo("text/plain");
            assertThat(upload.sizeBytes()).isEqualTo(3);
            assertThat(upload.sha256()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
            assertThat(upload.verdict()).isEqualTo(ScanVerdict.clean());
            assertThat(upload.file())
                    .isEqualTo(scanned.get())
                    .hasParent(tempDir)
                    .hasContent("abc");
            assertThat(upload.file().getFileName().toString()).doesNotContain("notes");
        }
        assertThat(upload.file()).doesNotExist();
        assertThat(tempFiles()).isZero();
    }

    @Test
    void infectedUploadIsNotAnErrorButComesBackQuarantined() throws IOException {
        try (ScannedUpload upload = intake(file -> ScanVerdict.found("Eicar-Test-Signature"))
                .receive("eicar.txt", bytes(UploadSamples.eicar()))) {
            assertThat(upload.verdict().status()).isEqualTo(ScanStatus.QUARANTINED);
            assertThat(upload.verdict().signature()).isEqualTo("Eicar-Test-Signature");
            assertThat(upload.file()).exists();
        }
    }

    @Test
    void uploadOfExactlyTheCapIsAccepted() throws IOException {
        byte[] content = new byte[CAP];
        Arrays.fill(content, (byte) 'a');
        try (ScannedUpload upload = clean().receive("big.txt", bytes(content))) {
            assertThat(upload.sizeBytes()).isEqualTo(CAP);
        }
    }

    @Test
    void oversizedUploadIsRefusedWithoutBeingReadToItsEndAndItsTempFileIsDeleted() throws IOException {
        AtomicLong read = new AtomicLong();
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                read.incrementAndGet();
                return 'a';
            }

            @Override
            public int read(byte[] b, int off, int len) {
                Arrays.fill(b, off, off + len, (byte) 'a');
                read.addAndGet(len);
                return len;
            }
        };

        assertThatThrownBy(() -> clean().receive("big.txt", endless))
                .isInstanceOfSatisfying(
                        AttachmentRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.TOO_LARGE));
        assertThat(read.get()).isGreaterThan(CAP).isLessThanOrEqualTo(CAP + 64 * 1024);
        assertThat(scanned.get()).isNull();
        assertThat(tempFiles()).isZero();
    }

    @Test
    void refusedTypeDeletesTheTempFileAndIsNeverScanned() throws IOException {
        assertThatThrownBy(() -> clean().receive("invoice.pdf", bytes(UploadSamples.windowsExecutable())))
                .isInstanceOfSatisfying(
                        AttachmentRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.TYPE_NOT_ALLOWED));
        assertThat(scanned.get()).isNull();
        assertThat(tempFiles()).isZero();
    }

    @Test
    void extensionMismatchDeletesTheTempFile() throws IOException {
        assertThatThrownBy(() -> clean().receive("memo.docx", bytes(UploadSamples.pdf())))
                .isInstanceOfSatisfying(
                        AttachmentRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.EXTENSION_MISMATCH));
        assertThat(tempFiles()).isZero();
    }

    @Test
    void oversizedImageDeletesTheTempFile() throws IOException {
        assertThatThrownBy(() -> clean().receive("bomb.png", bytes(UploadSamples.pngHeaderOnly(20_000, 20_000))))
                .isInstanceOfSatisfying(
                        AttachmentRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.IMAGE));
        assertThat(tempFiles()).isZero();
    }

    @Test
    void unavailableScannerRefusesTheUploadAndDeletesTheTempFile() throws IOException {
        UploadIntake intake = intake(file -> {
            assertThat(file).exists();
            throw new ScannerUnavailableException("clamd unreachable");
        });
        assertThatThrownBy(() -> intake.receive("notes.txt", bytes(UploadSamples.ascii("abc"))))
                .isInstanceOf(ScannerUnavailableException.class);
        assertThat(tempFiles()).isZero();
    }

    @Test
    void clamdDownRefusesTheUploadAndDeletesTheTempFile() throws IOException {
        int port;
        try (ServerSocket closed = new ServerSocket(0)) {
            port = closed.getLocalPort();
        }
        UploadIntake intake = intake(ClamdClientTest.client(port));
        assertThatThrownBy(() -> intake.receive("notes.txt", bytes(UploadSamples.ascii("abc"))))
                .isInstanceOf(ScannerUnavailableException.class);
        assertThat(tempFiles()).isZero();
    }

    @Test
    void uploadThatFailsMidStreamDeletesTheTempFile() throws IOException {
        InputStream broken = new InputStream() {
            private int left = 10;

            @Override
            public int read() throws IOException {
                if (left-- > 0) {
                    return 'a';
                }
                throw new IOException("client went away");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                b[off] = (byte) read();
                return 1;
            }
        };
        assertThatThrownBy(() -> clean().receive("notes.txt", broken)).isInstanceOf(UncheckedIOException.class);
        assertThat(tempFiles()).isZero();
    }

    @Test
    void unexpectedScannerFailureStillDeletesTheTempFile() throws IOException {
        UploadIntake intake = intake(file -> {
            throw new IllegalStateException("bug");
        });
        assertThatThrownBy(() -> intake.receive("notes.txt", bytes(UploadSamples.ascii("abc"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(tempFiles()).isZero();
    }

    @Test
    void blankOrOverlongNameIsRefusedBeforeAnythingIsWritten() throws IOException {
        for (String name : new String[] {" ", "a".repeat(252) + ".txt"}) {
            assertThatThrownBy(() -> clean().receive(name, bytes(UploadSamples.ascii("abc"))))
                    .isInstanceOfSatisfying(
                            AttachmentRejectedException.class,
                            e -> assertThat(e.reason()).isEqualTo(Reason.NAME));
        }
        assertThat(tempFiles()).isZero();
    }

    @Test
    void missingTempDirectoryFailsWithoutScanning() {
        UploadIntake intake = new UploadIntake(
                new AttachmentProperties(
                        tempDir.resolve("missing"),
                        DataSize.ofBytes(CAP),
                        25_000_000,
                        new AttachmentProperties.Clamd(
                                "127.0.0.1", 9, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1))),
                file -> {
                    scanned.set(file);
                    return ScanVerdict.clean();
                });
        assertThatThrownBy(() -> intake.receive("notes.txt", bytes(UploadSamples.ascii("abc"))))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(scanned.get()).isNull();
    }
}
