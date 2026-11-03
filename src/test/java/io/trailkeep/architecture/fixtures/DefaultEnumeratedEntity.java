// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.architecture.fixtures;

import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;

/** Violation: a bare {@code @Enumerated} defaults to ORDINAL. */
@Entity
public class DefaultEnumeratedEntity {

    @Id
    Long id;

    @Enumerated
    FixtureStatus status;
}
