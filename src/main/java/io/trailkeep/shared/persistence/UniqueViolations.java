// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.persistence;

import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Tells a unique-constraint violation from any other integrity failure, the same way on every database. A service
 * that checks "is this name free?" before it writes still loses a race to a concurrent request; the database's unique
 * constraint is the real authority, and this is how the service recognises its refusal (Hibernate classifies the
 * vendor's error code, so no message text or constraint name is parsed).
 */
public final class UniqueViolations {

    private UniqueViolations() {}

    /**
     * Whether the failure is a unique-constraint violation.
     *
     * @param failure what the flush or write threw
     * @return true if the database refused a duplicate key
     */
    public static boolean isUnique(DataIntegrityViolationException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && violation.getKind() == ConstraintKind.UNIQUE) {
                return true;
            }
        }
        return false;
    }
}
