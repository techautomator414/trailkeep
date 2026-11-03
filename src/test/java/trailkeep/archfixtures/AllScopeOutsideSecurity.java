// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package trailkeep.archfixtures;

import io.trailkeep.shared.access.CaseScope;

/** Violation: only the security module may grant the all-cases scope. */
class AllScopeOutsideSecurity {

    CaseScope grant() {
        return CaseScope.all();
    }
}
