// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.architecture.fixtures;

import io.trailkeep.shared.access.CaseRooted;
import org.springframework.data.repository.CrudRepository;

/** Violation: inherits unscoped {@code findById}/{@code findAll} from {@code CrudRepository}. */
@CaseRooted
interface CrudCaseRepository extends CrudRepository<FixtureEntity, Long> {}
