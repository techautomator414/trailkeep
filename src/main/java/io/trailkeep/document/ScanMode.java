// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

/**
 * Who scans uploads for malware: {@code app.attachments.scanning}, set by whoever deploys and read at startup
 * (ADR-0013). Changing it is a redeploy, so it shows in change control, and no account in the application can switch
 * scanning off.
 */
enum ScanMode {
    /** The default: every upload is scanned by clamd over TLS before it is stored, and refused without a verdict. */
    CLAMD,
    /**
     * The deployment scans upstream (a gateway or ICAP proxy) or at the endpoints. Trailkeep calls no scanner and
     * stores files as {@link ScanStatus#NOT_SCANNED}, never as {@link ScanStatus#CLEAN}.
     */
    EXTERNAL
}
