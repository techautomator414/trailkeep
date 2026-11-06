// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.security.CaseAction;
import io.trailkeep.security.CaseFacts;
import io.trailkeep.security.CasePermissionService;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Builds {@link PrincipalCaseScope}s by asking {@link CasePermissionService} about {@code VIEW}: for each
 * sensitivity, owned and not owned, first for a team the person is not in (a global role reads every team), then
 * for each of their teams. {@code VIEW} does not depend on status in the matrix; {@code CaseScopeMatrixIT} proves the
 * resulting predicate agrees with the matrix for every status.
 */
@Service
@CaseAccessPath
class DefaultCaseScopeService implements CaseScopeService {

    /** No team has this id (ids come from sequences starting at 1); probes roles that are not team roles. */
    private static final long NO_TEAM = -1;

    private final CasePermissionService permissions;
    private final CaseRecordRepository cases;

    DefaultCaseScopeService(CasePermissionService permissions, CaseRecordRepository cases) {
        this.permissions = permissions;
        this.cases = cases;
    }

    @Override
    public CaseScope forUser(UserPermissions user) {
        List<PrincipalCaseScope.Grant> grants = new ArrayList<>(4);
        for (Sensitivity sensitivity : Sensitivity.values()) {
            for (boolean owner : new boolean[] {false, true}) {
                if (views(user, NO_TEAM, sensitivity, owner)) {
                    grants.add(new PrincipalCaseScope.Grant(sensitivity, owner, true, Set.of()));
                    continue;
                }
                Set<Long> teams = user.teamRoles().keySet().stream()
                        .filter(team -> views(user, team, sensitivity, owner))
                        .collect(Collectors.toUnmodifiableSet());
                if (!teams.isEmpty()) {
                    grants.add(new PrincipalCaseScope.Grant(sensitivity, owner, false, teams));
                }
            }
        }
        return new PrincipalCaseScope(user.userId(), grants, cases::exists);
    }

    @Override
    public boolean readsOthersRestricted(UserPermissions user) {
        return views(user, NO_TEAM, Sensitivity.RESTRICTED, false)
                || user.teamRoles().keySet().stream()
                        .anyMatch(team -> views(user, team, Sensitivity.RESTRICTED, false));
    }

    @Override
    public Predicate readableCase(
            CaseScope scope, CaseNumber caseNumber, Expression<?> caseId, CriteriaQuery<?> query, CriteriaBuilder cb) {
        Root<CaseRecord> record = query.from(CaseRecord.class);
        return cb.and(
                cb.equal(caseId, record.get("id")),
                cb.equal(record.get("caseNumber"), caseNumber.toString()),
                scope.toPredicate(record, query, cb));
    }

    private boolean views(UserPermissions user, long teamId, Sensitivity sensitivity, boolean owner) {
        CaseFacts facts = new CaseFacts(teamId, owner ? user.userId() : null, sensitivity, CaseStatus.OPEN);
        return permissions.isAllowed(user, facts, CaseAction.VIEW);
    }
}
