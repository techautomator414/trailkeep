// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.user.UserPermissions;

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
}
