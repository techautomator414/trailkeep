// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.architecture.fixtures;

import io.trailkeep.shared.access.CaseRooted;
import java.util.List;
import org.springframework.data.repository.Repository;

/** Violation: a derived query without a scope parameter. */
@CaseRooted
interface UnscopedMethodCaseRepository extends Repository<FixtureEntity, Long> {

    List<FixtureEntity> findByStatus(FixtureStatus status);
}
