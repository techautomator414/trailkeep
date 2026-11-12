// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * An upload that passed validation and was scanned, still in its temp file: what the upload service stores. The
 * holder owns the temp file and must {@link #close()} it (try-with-resources), which deletes it. An imported file is
 * checked in place ({@link UploadIntake#receive(String, Path, long)}) and is not deleted: it belongs to the run.
 *
 * @param file the temp file holding the bytes
 * @param contentType the type detected from the bytes
 * @param sizeBytes the size in bytes
 * @param sha256 the lower-case hex SHA-256 of the bytes
 * @param verdict the scanner's verdict, or {@link ScanVerdict#notScanned()} in {@code external} mode
 * @param scanMode the scan mode in force when the file was received; the upload's audit entry records it (ADR-0013)
 * @param temporary whether {@link #close()} deletes the file: a temp copy of an upload, not an imported file
 */
record ScannedUpload(
        Path file,
        String contentType,
        long sizeBytes,
        String sha256,
        ScanVerdict verdict,
        ScanMode scanMode,
        boolean temporary)
        implements AutoCloseable {

    /**
     * Deletes the temp file.
     *
     * @throws UncheckedIOException if it cannot be deleted
     */
    @Override
    public void close() {
        if (!temporary) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("upload temp file not deleted", e);
        }
    }
}
