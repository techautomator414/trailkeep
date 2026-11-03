// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package trailkeep.archfixtures;

import org.springframework.data.repository.Repository;

/** Violation: repositories must be package-private. */
public interface PublicRepository extends Repository<FixtureEntity, Long> {}
