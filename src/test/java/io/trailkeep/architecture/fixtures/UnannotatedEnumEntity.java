// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.architecture.fixtures;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** Violation: an enum field without {@code @Enumerated} is persisted by ordinal. */
@Entity
public class UnannotatedEnumEntity {

    @Id
    Long id;

    FixtureStatus status;
}
