// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;

/**
 * Scans a file for malware. {@link ClamdClient} is the v1 implementation; the interface keeps another scanner (an
 * ICAP gateway, BUILD_PLAN risk register) a drop-in replacement.
 */
interface VirusScanner {

    /**
     * Scans one file, synchronously.
     *
     * @param file the file to scan
     * @return the verdict
     * @throws ScannerUnavailableException if the scanner gave no verdict
     */
    ScanVerdict scan(Path file);

    /**
     * Scans one file and hashes exactly the bytes the scan read, so a caller can prove what was scanned is what it
     * stores (WP-26 part g). {@link ClamdClient} hashes the very stream it sends. This default, for a scanner that
     * cannot, hashes the file as read just before the scan, which proves less.
     *
     * @param file the file to scan
     * @param sent receives every byte scanned
     * @return the verdict
     * @throws ScannerUnavailableException if the scanner gave no verdict
     */
    default ScanVerdict scan(Path file, MessageDigest sent) {
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), sent)) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException e) {
            throw new UncheckedIOException("file not read for its hash", e);
        }
        return scan(file);
    }
}
