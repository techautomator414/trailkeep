// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import io.trailkeep.shared.DomainException;

/**
 * An upload refused by validation, before it was scanned or stored: always {@link Kind#INVALID} (422). The
 * {@link #reason()} lets the upload form say what to fix; the message never contains the file name.
 */
public final class AttachmentRejectedException extends DomainException {

    private static final long serialVersionUID = 1L;

    /** Why the upload was refused. */
    public enum Reason {
        /** The name is blank or longer than the stored maximum. */
        NAME,
        /** The file is larger than {@code app.attachments.max-bytes}. */
        TOO_LARGE,
        /** The detected type is not on the allow-list (BUILD_PLAN §9), for example an executable. */
        TYPE_NOT_ALLOWED,
        /** The detected type is allowed but is not what the file's extension says. */
        EXTENSION_MISMATCH,
        /** An image whose header could not be read, or whose dimensions exceed the image cap. */
        IMAGE,
        /** An imported file whose content differs from the source's SHA-256, or changed after its scan (WP-26). */
        HASH_MISMATCH
    }

    private final Reason reason;

    AttachmentRejectedException(Reason reason, String message) {
        super(Kind.INVALID, message);
        this.reason = reason;
    }

    /**
     * Why the upload was refused.
     *
     * @return the reason
     */
    public Reason reason() {
        return reason;
    }
}
