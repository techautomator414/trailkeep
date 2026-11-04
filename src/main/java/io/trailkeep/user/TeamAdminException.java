// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

/** A refused team or membership change; the web layer maps {@link #kind()} to 404, 403, 409 or 422. */
public final class TeamAdminException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the change was refused. */
    public enum Kind {
        /** The team or user does not exist (404). */
        NOT_FOUND,
        /** The actor is not a System Admin (403). */
        FORBIDDEN,
        /** The change conflicts with current state, such as a duplicate team name (409). */
        CONFLICT,
        /** The change breaks a rule, such as giving a System Admin a team role (422). */
        INVALID
    }

    private final Kind kind;

    TeamAdminException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    /**
     * Why the change was refused.
     *
     * @return the kind
     */
    public Kind kind() {
        return kind;
    }
}
