// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package trailkeep.archfixtures;

import org.jspecify.annotations.Nullable;

/** Violation: holds a case-rooted repository in a field without {@code @CaseAccessPath}. */
class UnmarkedFieldHolder {

    private @Nullable CompliantCaseRepository repository;

    void set(Object value) {
        repository = (CompliantCaseRepository) value;
    }

    boolean ready() {
        return repository != null;
    }
}
