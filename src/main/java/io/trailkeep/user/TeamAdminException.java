// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import io.trailkeep.shared.DomainException;

/**
 * A refused team, membership or user-disable change. The shell's {@code ErrorAdvice} renders it: {@code NOT_FOUND}
 * 404 for an unknown team, user or membership, {@code FORBIDDEN} 403 for anyone but a System Admin, {@code CONFLICT}
 * 409 for a taken team name, {@code INVALID} 422 for a broken rule such as a team role for a System Admin.
 */
public final class TeamAdminException extends DomainException {

    private static final long serialVersionUID = 1L;

    TeamAdminException(Kind kind, String message) {
        super(kind, message);
    }
}
