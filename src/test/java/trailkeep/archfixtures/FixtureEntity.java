// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package trailkeep.archfixtures;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;
import org.jspecify.annotations.Nullable;

/** Compliant entity: enum stored as STRING; static and transient enum fields are ignored. */
@Entity
public class FixtureEntity {

    /** Not persisted; must not trip the enum rule. */
    static final FixtureStatus DEFAULT_STATUS = FixtureStatus.OPEN;

    @Id
    Long id;

    @Enumerated(EnumType.STRING)
    FixtureStatus status;

    @Transient
    @Nullable
    FixtureStatus previousStatus;
}
