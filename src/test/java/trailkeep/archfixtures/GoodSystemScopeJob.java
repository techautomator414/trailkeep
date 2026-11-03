// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package trailkeep.archfixtures;

import io.trailkeep.shared.access.CaseScope;

/** Compliant: any module may use the system scope for a job. */
class GoodSystemScopeJob {

    CaseScope scope() {
        return CaseScope.system();
    }
}
