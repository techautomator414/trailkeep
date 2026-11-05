// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.security.CaseAction;
import io.trailkeep.security.CaseFacts;
import io.trailkeep.security.CasePermissionService;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.user.UserPermissions;
import org.springframework.stereotype.Component;

/**
 * Loads a case for an action, in the one order every refusal follows (ADR-0005): 404 through the person's {@code
 * CaseScope} for a malformed, absent or unreadable case, then 403 if the matrix denies the action. Only after both
 * does a caller look at input (422) or version (409). It runs in its caller's transaction.
 */
@Component
@CaseAccessPath
class CaseLoader {

    private final CaseRecordRepository cases;
    private final CaseScopeService scopes;
    private final CasePermissionService permissions;

    CaseLoader(CaseRecordRepository cases, CaseScopeService scopes, CasePermissionService permissions) {
        this.cases = cases;
        this.scopes = scopes;
        this.permissions = permissions;
    }

    /**
     * A readable case on which the person may perform an action.
     *
     * @param user the person's permissions, rebuilt for this request
     * @param caseNumber the case number as it appears in the URL
     * @param action the action
     * @return the managed case
     */
    CaseRecord load(UserPermissions user, String caseNumber, CaseAction action) {
        CaseRecord record = cases.findOne(scopes.forUser(user), parse(caseNumber))
                .orElseThrow(() -> new CaseException(Kind.NOT_FOUND, "case not found"));
        if (!permissions.isAllowed(user, facts(record), action)) {
            throw new CaseException(Kind.FORBIDDEN, "action not allowed on this case");
        }
        return record;
    }

    /**
     * Whether the person reads the case now, as the database holds it in this transaction.
     *
     * @param user the person's permissions
     * @param number the case
     * @return {@code true} if the case is in their scope
     */
    boolean canRead(UserPermissions user, CaseNumber number) {
        return scopes.forUser(user).canRead(number);
    }

    /**
     * Parses a case number from a URL; a malformed one is "not found", like an absent case.
     *
     * @param caseNumber the text
     * @return the number
     */
    static CaseNumber parse(String caseNumber) {
        try {
            return CaseNumber.parse(caseNumber);
        } catch (IllegalArgumentException malformed) {
            throw new CaseException(Kind.NOT_FOUND, "not a case number");
        }
    }

    /**
     * The facts the matrix decides on.
     *
     * @param record the case
     * @return its team, owner, sensitivity and status
     */
    static CaseFacts facts(CaseRecord record) {
        return new CaseFacts(record.getTeamId(), record.getOwnerId(), record.getSensitivity(), record.getStatus());
    }
}
