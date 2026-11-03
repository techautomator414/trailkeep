// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package trailkeep.archfixtures;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;

/** Violation: an enum stored by ordinal. */
@Entity
public class OrdinalEntity {

    @Id
    Long id;

    @Enumerated(EnumType.ORDINAL)
    FixtureStatus status;
}
