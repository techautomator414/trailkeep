// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.access;

import io.trailkeep.shared.CaseNumber;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * The two unrestricted scopes. Distinct constants so that audit and logs can tell a job from a privileged user.
 * {@link #canRead} answers {@code true} for every case number; an absent case still yields an empty load.
 */
enum UnrestrictedScope implements CaseScope {
    SYSTEM,
    ALL;

    @Override
    public Predicate toPredicate(Root<?> caseRoot, CriteriaQuery<?> query, CriteriaBuilder cb) {
        return cb.conjunction();
    }

    @Override
    public boolean canRead(CaseNumber caseNumber) {
        return true;
    }
}
