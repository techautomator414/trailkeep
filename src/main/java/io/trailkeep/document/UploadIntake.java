// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import io.trailkeep.document.AttachmentRejectedException.Reason;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Takes an upload from the request to a scanned temp file (BUILD_PLAN §9, IMPLEMENTATION_PLAN A.1 #3): copy into
 * {@code app.attachments.temp-dir} while hashing and enforcing the size cap, detect and check the type, then scan
 * synchronously. Nothing here touches the database; the caller stores the result outside any network call.
 *
 * <p>The temp file is named by {@link Files#createTempFile}, never by the uploader, and is deleted on every failure
 * path before the exception leaves; on success its {@link ScannedUpload} owns it.
 */
@Component
class UploadIntake {

    private static final Logger log = LoggerFactory.getLogger(UploadIntake.class);

    private static final int BUFFER = 64 * 1024;

    private final AttachmentProperties properties;
    private final VirusScanner scanner;

    UploadIntake(AttachmentProperties properties, VirusScanner scanner) {
        this.properties = properties;
        this.scanner = scanner;
    }

    /**
     * Validates and scans one upload. An infected file is not an error: it comes back quarantined, to be stored as
     * evidence.
     *
     * @param fileName the name the uploader gave; only its length and extension are used here
     * @param content the uploaded bytes; the caller closes it
     * @return the scanned upload, owning its temp file
     * @throws AttachmentRejectedException if the name, size, type or image is refused (422)
     * @throws ScannerUnavailableException if the scanner gave no verdict; the upload is refused
     * @throws UncheckedIOException if the upload or the temp file cannot be read or written
     */
    ScannedUpload receive(String fileName, InputStream content) {
        if (fileName.isBlank() || fileName.length() > CaseAttachment.MAX_FILE_NAME) {
            throw new AttachmentRejectedException(Reason.NAME, "file name blank or too long");
        }
        Path temp = createTempFile();
        boolean handedOver = false;
        try {
            MessageDigest sha256 = sha256();
            long size = copy(content, temp, sha256);
            String type = AttachmentTypes.check(temp, fileName, properties.maxImagePixels());
            ScanVerdict verdict = scanner.scan(temp);
            ScannedUpload upload =
                    new ScannedUpload(temp, type, size, HexFormat.of().formatHex(sha256.digest()), verdict);
            handedOver = true;
            return upload;
        } finally {
            if (!handedOver) {
                delete(temp);
            }
        }
    }

    private Path createTempFile() {
        try {
            return Files.createTempFile(properties.tempDir(), "upload-", ".tmp");
        } catch (IOException e) {
            throw new UncheckedIOException("upload temp file not created", e);
        }
    }

    /** Copies at most the cap plus one buffer, so an oversized upload is never read to its end. */
    private long copy(InputStream content, Path temp, MessageDigest sha256) {
        long max = properties.maxBytes().toBytes();
        long total = 0;
        byte[] buffer = new byte[BUFFER];
        try (OutputStream out = Files.newOutputStream(temp)) {
            for (int n = content.read(buffer); n >= 0; n = content.read(buffer)) {
                total += n;
                if (total > max) {
                    throw new AttachmentRejectedException(Reason.TOO_LARGE, "upload exceeds the size cap");
                }
                sha256.update(buffer, 0, n);
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("upload not copied", e);
        }
        return total;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform provides SHA-256", e);
        }
    }

    private static void delete(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            // No name in the log: the temp name is random, and the uploader's name is never logged.
            log.warn("An upload temp file could not be deleted; the emptyDir is cleared when the pod restarts", e);
        }
    }
}
