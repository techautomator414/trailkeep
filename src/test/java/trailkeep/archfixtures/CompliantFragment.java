// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package trailkeep.archfixtures;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseScope;
import java.util.List;
import java.util.Optional;

/** Custom fragment whose methods all take a scope. */
interface CompliantFragment {

    Optional<FixtureEntity> findOne(CaseScope scope, CaseNumber caseNumber);

    List<FixtureEntity> findOpen(CaseScope scope, int limit);
}
