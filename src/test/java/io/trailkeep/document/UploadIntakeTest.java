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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
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

    /** An import run's content area; imported files are checked where they lie, never copied to the temp dir. */
    @TempDir
    Path contentArea;

    private final AtomicReference<Path> scanned = new AtomicReference<>();

    private AttachmentProperties properties(Path dir, ScanMode mode) {
        return new AttachmentProperties(
                dir,
                DataSize.ofBytes(CAP),
                25_000_000,
                mode,
                new AttachmentProperties.Clamd(
                        "127.0.0.1", 9, "clamd", Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)),
                8);
    }

    private UploadIntake intake(VirusScanner scanner) {
        return new UploadIntake(properties(tempDir, ScanMode.CLAMD), Optional.of(scanner));
    }

    /** External mode, given a scanner that fails the test if it is ever called. */
    private UploadIntake external() {
        return new UploadIntake(properties(tempDir, ScanMode.EXTERNAL), Optional.of(file -> {
            scanned.set(file);
            throw new AssertionError("no scanner is called in external mode");
        }));
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
            assertThat(upload.scanMode()).isEqualTo(ScanMode.CLAMD);
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
        UploadIntake intake = intake(ClamdClientTest.client(ClamdClientTest.closedPort()));
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
    void controlCharactersInTheNameAreRefusedBeforeAnythingIsWritten() throws IOException {
        for (String name :
                new String[] {"a" + (char) 0 + ".txt", "a\r\nb.txt", "a\nb.txt", "a\tb.txt", "a" + (char) 0x7F + ".txt"
                }) {
            assertThatThrownBy(() -> clean().receive(name, bytes(UploadSamples.ascii("abc"))))
                    .isInstanceOfSatisfying(
                            AttachmentRejectedException.class,
                            e -> assertThat(e.reason()).isEqualTo(Reason.NAME));
        }
        assertThat(tempFiles()).isZero();
        assertThat(scanned.get()).isNull();
    }

    @Test
    void missingTempDirectoryFailsWithoutScanning() {
        UploadIntake intake =
                new UploadIntake(properties(tempDir.resolve("missing"), ScanMode.CLAMD), Optional.of(file -> {
                    scanned.set(file);
                    return ScanVerdict.clean();
                }));
        assertThatThrownBy(() -> intake.receive("notes.txt", bytes(UploadSamples.ascii("abc"))))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(scanned.get()).isNull();
    }

    @Test
    void clamdModeWithoutScannerFailsAtConstruction() {
        assertThatThrownBy(() -> new UploadIntake(properties(tempDir, ScanMode.CLAMD), Optional.empty()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void externalModeStoresValidFileAsNotScannedWithoutCallingAnyScanner() throws IOException {
        ScannedUpload upload = external().receive("notes.txt", bytes(UploadSamples.ascii("abc")));
        try (upload) {
            assertThat(upload.verdict()).isEqualTo(ScanVerdict.notScanned());
            assertThat(upload.verdict().status()).isNotEqualTo(ScanStatus.CLEAN);
            assertThat(upload.scanMode()).isEqualTo(ScanMode.EXTERNAL);
            assertThat(upload.contentType()).isEqualTo("text/plain");
            assertThat(upload.sha256()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
            assertThat(upload.file()).hasContent("abc");
        }
        assertThat(scanned.get()).isNull();
        assertThat(tempFiles()).isZero();
    }

    @Test
    void externalModeStillRefusesDisguisedExecutable() throws IOException {
        assertThatThrownBy(() -> external().receive("invoice.pdf", bytes(UploadSamples.windowsExecutable())))
                .isInstanceOfSatisfying(
                        AttachmentRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.TYPE_NOT_ALLOWED));
        assertThatThrownBy(() -> external().receive("memo.docx", bytes(UploadSamples.pdf())))
                .isInstanceOfSatisfying(
                        AttachmentRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.EXTENSION_MISMATCH));
        assertThat(scanned.get()).isNull();
        assertThat(tempFiles()).isZero();
    }

    @Test
    void externalModeStillEnforcesTheSizeCap() throws IOException {
        byte[] content = new byte[CAP + 1];
        Arrays.fill(content, (byte) 'a');
        assertThatThrownBy(() -> external().receive("big.txt", bytes(content)))
                .isInstanceOfSatisfying(
                        AttachmentRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.TOO_LARGE));
        assertThat(scanned.get()).isNull();
        assertThat(tempFiles()).isZero();
    }

    @Test
    void externalModeStillEnforcesTheImageCap() throws IOException {
        assertThatThrownBy(() -> external().receive("bomb.png", bytes(UploadSamples.pngHeaderOnly(20_000, 20_000))))
                .isInstanceOfSatisfying(
                        AttachmentRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.IMAGE));
        assertThat(scanned.get()).isNull();
        assertThat(tempFiles()).isZero();
    }

    @Test
    void importedFileIsCheckedWhereItLiesAndHashedFromTheScannedStream() throws IOException {
        Path file = Files.write(contentArea.resolve("notes.txt"), UploadSamples.ascii("abc"));
        try (ScannedUpload upload = clean().receive("notes.txt", file, CAP)) {
            assertThat(upload.sha256()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
            assertThat(upload.verdict()).isEqualTo(ScanVerdict.clean());
            assertThat(upload.temporary()).isFalse();
        }
        assertThat(scanned.get()).isEqualTo(file);
        assertThat(file).hasContent("abc");
        assertThat(tempFiles()).isZero();
    }

    @Test
    void importedFileSwappedBetweenItsTypeCheckAndItsScanIsRefused() throws IOException {
        Path file = Files.write(contentArea.resolve("notes.txt"), UploadSamples.ascii("abc"));
        VirusScanner swapping = new VirusScanner() {
            @Override
            public ScanVerdict scan(Path scannedFile) {
                return ScanVerdict.clean();
            }

            // Replaces the content after the type pass, then scans and hashes the new bytes.
            @Override
            public ScanVerdict scan(Path scannedFile, MessageDigest sent) {
                try {
                    Files.write(scannedFile, UploadSamples.ascii("xyz"));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return VirusScanner.super.scan(scannedFile, sent);
            }
        };

        assertThatThrownBy(() -> intake(swapping).receive("notes.txt", file, CAP))
                .isInstanceOfSatisfying(
                        AttachmentRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.HASH_MISMATCH));
    }
}
