// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

/**
 * What the antivirus scan, and any release after it, made of an attachment (IMPLEMENTATION_PLAN A.1 #3). A file is
 * scanned before it is stored, so there is no pending state; in {@code external} scan mode (ADR-0013) nothing in
 * Trailkeep scans it, and it says so.
 */
public enum ScanStatus {
    /** The scanner found nothing; the file may be downloaded. */
    CLEAN,
    /** The scanner found a signature; the file is kept as evidence and never served. */
    QUARANTINED,
    /** Quarantined, then released by a Compliance Admin with a reason; the file may be downloaded. */
    RELEASED,
    /**
     * Stored while {@code app.attachments.scanning} was {@code external} (ADR-0013): Trailkeep did not scan it and
     * never calls it clean. Scanning was delegated upstream or to the endpoints, so the file may be downloaded; there
     * is nothing to release. Shown as "Not scanned by Trailkeep (scanned upstream)".
     */
    NOT_SCANNED;

    /**
     * Whether a file in this state may be downloaded. Only a quarantined file is withheld; a release applies to it
     * alone.
     *
     * @return {@code true} for {@link #CLEAN}, {@link #RELEASED} and {@link #NOT_SCANNED}
     */
    public boolean isDownloadable() {
        return switch (this) {
            case CLEAN, RELEASED, NOT_SCANNED -> true;
            case QUARANTINED -> false;
        };
    }
}
