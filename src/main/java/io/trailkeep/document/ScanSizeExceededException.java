// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

/**
 * clamd refused the file as larger than its {@code StreamMaxLength} (design R13). For an upload this is the scanner
 * failing, as before; the importer tells it apart from an outage, reports the file as an issue and goes on with the
 * load instead of stopping it.
 */
public final class ScanSizeExceededException extends ScannerUnavailableException {

    private static final long serialVersionUID = 1L;

    ScanSizeExceededException() {
        super("clamd refused the file as larger than its stream limit");
    }
}
