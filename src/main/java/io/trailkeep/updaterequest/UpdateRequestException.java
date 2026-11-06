// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.updaterequest;

import io.trailkeep.shared.DomainException;

/**
 * A refused update request, rendered by the shell's {@code ErrorAdvice}: {@code NOT_FOUND} 404 for a request that is
 * not on the case, {@code INVALID} 422 for a bad message or recipient, {@code CONFLICT} 409 for a second open request
 * to the same person or a request already answered.
 */
public final class UpdateRequestException extends DomainException {

    private static final long serialVersionUID = 1L;

    UpdateRequestException(Kind kind, String message) {
        super(kind, message);
    }
}
