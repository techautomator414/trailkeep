// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;

/**
 * Who may list archived cases (BUILD_PLAN §4.6: "Manager+ can include archived cases with a filter"): a Manager of
 * some team or a Compliance Admin, the people the export offers details to. The list still reads through the
 * person's {@code CaseScope}, so the filter never widens what they read; without it, archived cases are left out of
 * every list, search and dashboard count, and opened only by their link.
 */
final class ArchivedCases {

    private ArchivedCases() {}

    /**
     * Whether the person may include archived cases in a list.
     *
     * @param user the person's permissions
     * @return {@code true} for a Manager of some team or a Compliance Admin
     */
    static boolean offered(UserPermissions user) {
        return user.has(GlobalRole.COMPLIANCE_ADMIN) || user.teamRoles().containsValue(TeamRole.MANAGER);
    }

    /**
     * Refuses a filter naming {@code ARCHIVED} from someone it is not offered to: {@code INVALID}, as for any value
     * the form does not offer them.
     *
     * @param user the person's permissions
     * @param filter the filter
     */
    static void check(UserPermissions user, CaseFilter filter) {
        if (filter.statuses().contains(CaseStatus.ARCHIVED) && !offered(user)) {
            throw new CaseException(Kind.INVALID, "archived cases are listed for Managers and Compliance Admins");
        }
    }
}
