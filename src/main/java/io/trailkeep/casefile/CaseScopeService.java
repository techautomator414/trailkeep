// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

/**
 * The principal-backed {@link CaseScope} (ADR-0005, Part A #6). A scope reads exactly the cases the permission matrix
 * lets the person {@code VIEW}; anything else, including a case that does not exist, is "not found".
 */
public interface CaseScopeService {

    /**
     * The scope of a person for the current request.
     *
     * @param user the person's permissions, rebuilt for this request; a disabled person reads nothing
     * @return their scope; never unrestricted, so case-less audit events stay out of reach
     */
    CaseScope forUser(UserPermissions user);

    /**
     * Whether a person reads restricted cases beyond their own: the permission matrix lets them {@code VIEW} a
     * restricted case they do not own, in some team or in every team (a team's Manager, a Compliance Admin). An
     * Analyst, who reads only the restricted cases they own, does not. The cases list offers its sensitivity filter
     * only to such a person; the filter itself never reaches beyond the scope.
     *
     * @param user the person's permissions, rebuilt for this request
     * @return {@code true} if they read some other person's restricted cases
     */
    boolean readsOthersRestricted(UserPermissions user);

    /**
     * Restricts a query over a table whose rows hold their case as an id, in another module, to one case the scope
     * reads (ADR-0005). It adds a {@code CaseRecord} root to the query, joins it on {@code caseId}, matches the case
     * number and applies {@link CaseScope#toPredicate}, so the case entity never leaves this module. A
     * {@code @CaseRooted} repository's Criteria method passes its scope and its own case-id path.
     *
     * @param scope the caller's scope
     * @param caseNumber the one case wanted
     * @param caseId the querying row's case-id attribute
     * @param query the query being built; it gains a root
     * @param cb the criteria builder
     * @return a predicate true exactly for rows of that case when the scope reads it
     */
    Predicate readableCase(
            CaseScope scope, CaseNumber caseNumber, Expression<?> caseId, CriteriaQuery<?> query, CriteriaBuilder cb);

    /**
     * Restricts a query over such a table to rows of any case the scope reads: {@code caseId IN} a subquery of the
     * readable cases' ids, for a list across cases (such as a person's open update requests).
     *
     * @param scope the caller's scope
     * @param caseId the querying row's case-id attribute
     * @param query the query being built
     * @param cb the criteria builder
     * @return a predicate true exactly for rows of readable cases
     */
    Predicate readableCases(CaseScope scope, Expression<?> caseId, CriteriaQuery<?> query, CriteriaBuilder cb);
}
