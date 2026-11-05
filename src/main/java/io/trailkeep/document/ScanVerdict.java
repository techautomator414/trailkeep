// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import org.jspecify.annotations.Nullable;

/**
 * A scanner's verdict on one file: {@link ScanStatus#CLEAN}, or {@link ScanStatus#QUARANTINED} with the signature
 * found; or {@link ScanStatus#NOT_SCANNED} when scanning is {@code external} (ADR-0013) and no scanner was asked. It
 * satisfies what {@link CaseAttachment#scanned} accepts.
 *
 * @param status {@code CLEAN}, {@code QUARANTINED} or {@code NOT_SCANNED}
 * @param signature the signature name for a quarantined file, at most {@value #MAX_SIGNATURE} characters
 */
record ScanVerdict(ScanStatus status, @Nullable String signature) {

    /** The longest signature kept, the width of {@code SCAN_DETAIL}. */
    static final int MAX_SIGNATURE = 200;

    ScanVerdict {
        if (status == ScanStatus.RELEASED || (status == ScanStatus.QUARANTINED) != (signature != null)) {
            throw new IllegalArgumentException("a verdict is clean or not scanned, or quarantined with a signature");
        }
        if (signature != null && (signature.isBlank() || signature.length() > MAX_SIGNATURE)) {
            throw new IllegalArgumentException("a signature is 1 to " + MAX_SIGNATURE + " characters");
        }
    }

    /**
     * Nothing found.
     *
     * @return the clean verdict
     */
    static ScanVerdict clean() {
        return new ScanVerdict(ScanStatus.CLEAN, null);
    }

    /**
     * No scan by Trailkeep: scanning is delegated upstream ({@code external} mode). Never a clean verdict.
     *
     * @return the not-scanned verdict
     */
    static ScanVerdict notScanned() {
        return new ScanVerdict(ScanStatus.NOT_SCANNED, null);
    }

    /**
     * A signature found.
     *
     * @param signature the scanner's signature name
     * @return the quarantined verdict
     */
    static ScanVerdict found(String signature) {
        return new ScanVerdict(ScanStatus.QUARANTINED, signature);
    }
}
