// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package trailkeep.archfixtures;

import java.util.Optional;

/** A fragment with an unscoped method. */
interface UnscopedFragment {

    Optional<FixtureEntity> findAnyById(Long id);
}
