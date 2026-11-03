// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package trailkeep.archfixtures;

import io.trailkeep.shared.access.CaseRooted;
import io.trailkeep.shared.access.CaseScope;
import org.springframework.data.repository.Repository;

/** Compliant: package-private, extends only {@code Repository}, every method scoped. */
@CaseRooted
interface CompliantCaseRepository extends Repository<FixtureEntity, Long>, CompliantFragment {

    long countReadable(CaseScope scope);
}
