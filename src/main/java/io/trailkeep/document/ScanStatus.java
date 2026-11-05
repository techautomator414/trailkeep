// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

/**
 * What the antivirus scan, and any release after it, made of an attachment (IMPLEMENTATION_PLAN A.1 #3). A file is
 * scanned before it is stored, so there is no pending state.
 */
public enum ScanStatus {
    /** The scanner found nothing; the file may be downloaded. */
    CLEAN,
    /** The scanner found a signature; the file is kept as evidence and never served. */
    QUARANTINED,
    /** Quarantined, then released by a Compliance Admin with a reason; the file may be downloaded. */
    RELEASED;

    /**
     * Whether a file in this state may be downloaded.
     *
     * @return {@code true} for {@link #CLEAN} and {@link #RELEASED}
     */
    public boolean isDownloadable() {
        return this != QUARANTINED;
    }
}
