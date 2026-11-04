// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.DomainException;

/**
 * A refused case request, rendered by the shell's {@code ErrorAdvice}: {@code NOT_FOUND} 404 for a case number that
 * is malformed, absent or unreadable (the three are indistinguishable), {@code FORBIDDEN} 403 for a denied action,
 * {@code INVALID} 422 for a reference the form could not have offered (an inactive case type or team, an owner who
 * may not own the case, a future identified-on date).
 */
public final class CaseException extends DomainException {

    private static final long serialVersionUID = 1L;

    CaseException(Kind kind, String message) {
        super(kind, message);
    }
}
