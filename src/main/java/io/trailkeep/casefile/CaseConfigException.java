// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.DomainException;

/**
 * A refused configuration change, rendered by the shell's {@code ErrorAdvice}: {@code NOT_FOUND} 404 for an unknown
 * policy or case type, {@code FORBIDDEN} 403 for anyone but a Compliance Admin, {@code CONFLICT} 409 for a taken
 * name, {@code INVALID} 422 for an unknown policy reference or repeated outcomes.
 */
public final class CaseConfigException extends DomainException {

    private static final long serialVersionUID = 1L;

    CaseConfigException(Kind kind, String message) {
        super(kind, message);
    }
}
