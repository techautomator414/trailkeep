// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared;

/**
 * A rule a request broke, as the web layer maps it (Part B "Web"): {@link Kind#NOT_FOUND} 404 (also for a case the
 * user may not read), {@link Kind#FORBIDDEN} 403 (a denied action on a readable case), {@link Kind#CONFLICT} 409,
 * {@link Kind#INVALID} 422. The message is for logs only; it is never shown to the user.
 */
public abstract class DomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** How the web layer answers. */
    public enum Kind {
        /** 404. */
        NOT_FOUND,
        /** 403. */
        FORBIDDEN,
        /** 409. */
        CONFLICT,
        /** 422. */
        INVALID
    }

    private final Kind kind;

    /**
     * Creates the exception.
     *
     * @param kind how the web layer answers
     * @param message a log message without sensitive content
     */
    protected DomainException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    /**
     * How the web layer answers.
     *
     * @return the kind
     */
    public Kind kind() {
        return kind;
    }
}
