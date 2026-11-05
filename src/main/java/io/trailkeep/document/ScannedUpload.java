// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * An upload that passed validation and was scanned, still in its temp file: what the upload service stores. The
 * holder owns the temp file and must {@link #close()} it (try-with-resources), which deletes it.
 *
 * @param file the temp file holding the bytes
 * @param contentType the type detected from the bytes
 * @param sizeBytes the size in bytes
 * @param sha256 the lower-case hex SHA-256 of the bytes
 * @param verdict the scanner's verdict
 */
record ScannedUpload(Path file, String contentType, long sizeBytes, String sha256, ScanVerdict verdict)
        implements AutoCloseable {

    /**
     * Deletes the temp file.
     *
     * @throws UncheckedIOException if it cannot be deleted
     */
    @Override
    public void close() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("upload temp file not deleted", e);
        }
    }
}
