// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import io.trailkeep.shared.DomainException;

/**
 * A refused download or release, rendered by the shell's {@code ErrorAdvice}: {@code NOT_FOUND} 404 for an id naming
 * no attachment of a readable case (so ids cannot be probed across cases), {@code FORBIDDEN} 403 for a quarantined
 * file, {@code INVALID} 422 for a release reason out of bounds or a file that is not quarantined.
 */
public final class AttachmentException extends DomainException {

    private static final long serialVersionUID = 1L;

    AttachmentException(Kind kind, String message) {
        super(kind, message);
    }
}
