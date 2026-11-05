// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import java.nio.file.Path;

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
}
