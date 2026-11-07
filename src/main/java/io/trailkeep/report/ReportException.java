// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import io.trailkeep.shared.DomainException;

/** A refused report, rendered by the shell's {@code ErrorAdvice}: 403 without a role, 422 for a bad period. */
public final class ReportException extends DomainException {

    private static final long serialVersionUID = 1L;

    ReportException(Kind kind, String message) {
        super(kind, message);
    }
}
