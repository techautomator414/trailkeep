// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package trailkeep.archfixtures;

import io.trailkeep.shared.access.CaseRooted;
import org.springframework.data.repository.Repository;

/** Violation: inherits an unscoped method from its custom fragment. */
@CaseRooted
interface UnscopedFragmentCaseRepository extends Repository<FixtureEntity, Long>, UnscopedFragment {}
