// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import org.jspecify.annotations.Nullable;

/** The malware scanner as the System screen shows it (WP-25, BUILD_PLAN §4.6 "scan engine status"). */
public interface ScanEngineService {

    /** What the screen says about the scanner. */
    enum State {
        /** clamd answered: uploads are scanned. */
        AVAILABLE,
        /** clamd did not answer: uploads are refused until it does. */
        UNAVAILABLE,
        /** {@code app.attachments.scanning: external}: Trailkeep calls no scanner and stores files as not scanned. */
        EXTERNAL
    }

    /**
     * The scanner's status.
     *
     * @param state available, unavailable or external
     * @param version clamd's version line (ClamAV release, signature database version and date) when available
     */
    record Status(State state, @Nullable String version) {}

    /**
     * Asks clamd for its version now, over the same TLS connection and deadlines as a scan.
     *
     * @return the status
     */
    Status status();
}
