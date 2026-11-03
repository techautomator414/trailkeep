// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared;

/**
 * Thrown when a case does not exist <em>or</em> the current scope may not read it. The two causes are deliberately
 * indistinguishable, so callers map this exception to HTTP 404 and case numbers cannot be enumerated (ADR-0005).
 */
public class CaseNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final CaseNumber caseNumber;

    /**
     * Creates the exception.
     *
     * @param caseNumber the case number that was asked for
     */
    public CaseNotFoundException(CaseNumber caseNumber) {
        super("Case " + caseNumber + " not found");
        this.caseNumber = caseNumber;
    }

    /**
     * Returns the case number that was asked for.
     *
     * @return the case number that was asked for
     */
    public CaseNumber caseNumber() {
        return caseNumber;
    }
}
