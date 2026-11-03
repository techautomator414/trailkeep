// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package trailkeep.archfixtures;

import io.trailkeep.shared.access.CaseAccessPath;

/** Compliant: an access path may hold a case-rooted repository. */
@CaseAccessPath
class CompliantAccessPath {

    private final CompliantCaseRepository repository;

    CompliantAccessPath(CompliantCaseRepository repository) {
        this.repository = repository;
    }

    CompliantCaseRepository repository() {
        return repository;
    }
}
