// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.closure;

import io.trailkeep.shared.DomainException;

/** A refused closure request, rendered by the shell's {@code ErrorAdvice}: {@code INVALID} 422 for bad input. */
public final class ClosureException extends DomainException {

    private static final long serialVersionUID = 1L;

    ClosureException(Kind kind, String message) {
        super(kind, message);
    }
}
