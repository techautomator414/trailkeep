// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.shared.access.CaseScope;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The scope of one person on one request. Built by {@link DefaultCaseScopeService} from the permission matrix, so
 * the predicate cannot drift from {@code matrix.csv}: each {@link Grant} says "cases of this sensitivity, which I
 * do (or do not) own, in these teams (or in every team)". A case is readable when any grant matches it.
 */
final class PrincipalCaseScope implements CaseScope {

    /**
     * One readable slice of cases.
     *
     * @param sensitivity the cases' sensitivity
     * @param owner whether the slice is the cases the person owns ({@code true}) or the others ({@code false})
     * @param everyTeam whether the slice covers every team, for global roles such as Compliance Admin
     * @param teams the teams covered when {@code everyTeam} is {@code false}; never empty then
     */
    record Grant(Sensitivity sensitivity, boolean owner, boolean everyTeam, Set<Long> teams) {
        Grant {
            teams = Set.copyOf(teams);
        }
    }

    /** Answers {@link #canRead} for this scope; a database lookup in production. */
    @FunctionalInterface
    interface ReadCheck {
        boolean canRead(CaseScope scope, CaseNumber caseNumber);
    }

    private final long userId;
    private final List<Grant> grants;
    private final ReadCheck readCheck;

    PrincipalCaseScope(long userId, List<Grant> grants, ReadCheck readCheck) {
        this.userId = userId;
        this.grants = List.copyOf(grants);
        this.readCheck = readCheck;
    }

    @Override
    public Predicate toPredicate(Root<?> caseRoot, CriteriaQuery<?> query, CriteriaBuilder cb) {
        List<Predicate> any = new ArrayList<>(grants.size());
        for (Grant grant : grants) {
            List<Predicate> all = new ArrayList<>(3);
            all.add(cb.equal(caseRoot.get("sensitivity"), grant.sensitivity()));
            all.add(
                    grant.owner()
                            ? cb.equal(caseRoot.get("ownerId"), userId)
                            : cb.notEqual(caseRoot.get("ownerId"), userId));
            if (!grant.everyTeam()) {
                all.add(caseRoot.get("teamId").in(grant.teams()));
            }
            any.add(cb.and(all.toArray(Predicate[]::new)));
        }
        return any.isEmpty() ? cb.disjunction() : cb.or(any.toArray(Predicate[]::new));
    }

    @Override
    public boolean canRead(CaseNumber caseNumber) {
        return !grants.isEmpty() && readCheck.canRead(this, caseNumber);
    }

    List<Grant> grants() {
        return grants;
    }

    @Override
    public String toString() {
        return "PrincipalCaseScope[user=" + userId + ", grants=" + grants + "]";
    }
}
