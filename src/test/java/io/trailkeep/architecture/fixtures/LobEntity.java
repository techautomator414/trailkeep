// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.architecture.fixtures;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;

/** Violation: {@code @Lob} is banned (BUILD_PLAN section 8). */
@Entity
public class LobEntity {

    @Id
    Long id;

    @Lob
    String body;
}
