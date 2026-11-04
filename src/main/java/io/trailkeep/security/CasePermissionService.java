// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import io.trailkeep.user.UserPermissions;
import java.util.List;

/**
 * Every case permission decision (BUILD_PLAN §3.2), evaluated from the decision table {@code
 * permissions/matrix.csv}. Callers pass permissions rebuilt for the current request.
 */
public interface CasePermissionService {

    /**
     * Whether the user may perform an action on a case.
     *
     * @param user the user's permissions
     * @param facts the case
     * @param action the action; {@link CaseAction#CREATE} is answered for {@code facts.teamId()}
     * @return {@code true} if the matrix allows it
     */
    boolean isAllowed(UserPermissions user, CaseFacts facts, CaseAction action);

    /**
     * Whether the user may own a case of a team: they must hold Analyst or Manager in it (a Compliance Admin
     * owns only as a member). A System Admin holds no team role, so never qualifies.
     *
     * @param user the user's permissions
     * @param teamId the case's team
     * @return {@code true} if the user may be the owner
     */
    boolean mayOwn(UserPermissions user, long teamId);

    /**
     * Who may decide a closure request: the team's enabled Managers minus the requester and the owner, or, if
     * that leaves nobody, the enabled Compliance Admins minus the requester and the owner.
     *
     * @param facts the case
     * @param requesterId who requested closure
     * @return the candidate approvers' ids, ascending
     */
    List<Long> approverCandidates(CaseFacts facts, long requesterId);
}
