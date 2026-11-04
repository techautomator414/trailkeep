// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.access;

import io.trailkeep.shared.CaseNotFoundException;
import io.trailkeep.shared.CaseNumber;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * The set of cases a caller may read (ADR-0005). Every {@link CaseRooted} repository method takes one and applies
 * {@link #toPredicate}; single-case loads follow with {@link #assertReadable}.
 *
 * <p>Not sealed: the principal-backed implementation lives in {@code casefile} (WP-11), outside this package.
 * Implementations are immutable and restrict by case id only, so one scope works for every case-rooted query.
 */
public interface CaseScope {

    /**
     * Restricts a query to readable cases.
     *
     * @param caseRoot the {@code CASE_RECORD} entity root, or a join from a case-rooted entity to it
     * @param query the query being built, available for subqueries
     * @param cb the criteria builder
     * @return a predicate on {@code caseRoot}'s id that is true exactly for readable cases
     */
    Predicate toPredicate(Root<?> caseRoot, CriteriaQuery<?> query, CriteriaBuilder cb);

    /**
     * Tells whether this scope permits reading a case. An implementation may also answer {@code false} for an
     * absent case; callers treat {@code false} and absent alike (404) and never reveal which applied.
     *
     * @param caseNumber the case number
     * @return {@code true} if this scope permits reading the case
     */
    boolean canRead(CaseNumber caseNumber);

    /**
     * Tells whether this scope reaches every case and also records that belong to no case, such as audit events for
     * logins and admin changes (Part A #6). Only {@link #system()} and {@link #all()} answer {@code true}; a
     * principal-backed scope never does, so queries over case-less rows fail closed for it.
     *
     * @return {@code true} for the unrestricted scopes
     */
    default boolean isUnrestricted() {
        return false;
    }

    /**
     * Asserts that this scope permits reading a case.
     *
     * @param caseNumber the case number
     * @throws CaseNotFoundException if {@link #canRead} answers {@code false}; the exception is the same one an
     *     absent case produces
     */
    default void assertReadable(CaseNumber caseNumber) {
        if (!canRead(caseNumber)) {
            throw new CaseNotFoundException(caseNumber);
        }
    }

    /**
     * The unrestricted scope for scheduled jobs and other work no principal initiated. Never use it on a request
     * thread.
     *
     * @return the system scope
     */
    static CaseScope system() {
        return UnrestrictedScope.SYSTEM;
    }

    /**
     * The unrestricted scope for a principal entitled to every case and to audit events with no case (Compliance
     * Admins). Granted only by the {@code security} module, which checks the role first; ArchUnit rejects calls
     * from anywhere else. The principal-aware variant replaces this in WP-11.
     *
     * @return the all-cases scope
     */
    static CaseScope all() {
        return UnrestrictedScope.ALL;
    }
}
