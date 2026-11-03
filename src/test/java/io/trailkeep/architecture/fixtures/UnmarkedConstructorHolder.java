// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.architecture.fixtures;

/** Violation: receives a case-rooted repository through its constructor without {@code @CaseAccessPath}. */
class UnmarkedConstructorHolder {

    private final Object repository;

    UnmarkedConstructorHolder(CompliantCaseRepository repository) {
        this.repository = repository;
    }

    Object repository() {
        return repository;
    }
}
