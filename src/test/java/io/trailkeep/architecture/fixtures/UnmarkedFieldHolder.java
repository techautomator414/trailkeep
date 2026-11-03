// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.architecture.fixtures;

/** Violation: holds a case-rooted repository in a field without {@code @CaseAccessPath}. */
class UnmarkedFieldHolder {

    private CompliantCaseRepository repository;

    void set(Object value) {
        repository = (CompliantCaseRepository) value;
    }

    boolean ready() {
        return repository != null;
    }
}
