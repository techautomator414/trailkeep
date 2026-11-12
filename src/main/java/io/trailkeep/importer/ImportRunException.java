// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.shared.DomainException;

/**
 * A refused Import runs request, rendered by the shell's {@code ErrorAdvice}: {@code FORBIDDEN} 403 for anyone but a
 * System Admin or a Compliance Admin, {@code NOT_FOUND} 404 for an unknown run.
 */
public final class ImportRunException extends DomainException {

    private static final long serialVersionUID = 1L;

    ImportRunException(Kind kind, String message) {
        super(kind, message);
    }
}
