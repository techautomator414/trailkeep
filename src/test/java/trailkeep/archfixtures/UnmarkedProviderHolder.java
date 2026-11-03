// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package trailkeep.archfixtures;

import java.util.function.Supplier;

/** Violation: wraps a case-rooted repository in a generic type to slip past a raw-type check. */
class UnmarkedProviderHolder {

    private final Supplier<CompliantCaseRepository> repository;

    UnmarkedProviderHolder(Supplier<CompliantCaseRepository> repository) {
        this.repository = repository;
    }

    Supplier<CompliantCaseRepository> repository() {
        return repository;
    }
}
