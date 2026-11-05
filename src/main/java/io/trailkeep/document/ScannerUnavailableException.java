// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

/**
 * The antivirus scanner gave no verdict: it was unreachable, timed out, or answered with an error or with something
 * unexpected. The upload is refused and the user asked to try again; a file is never stored unscanned
 * (IMPLEMENTATION_PLAN A.1 #3).
 */
public final class ScannerUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ScannerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    ScannerUnavailableException(String message) {
        super(message);
    }
}
