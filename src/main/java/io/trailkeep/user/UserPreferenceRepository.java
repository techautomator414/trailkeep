// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Preferences, always looked up by their person. Not case-rooted; module-internal. */
interface UserPreferenceRepository extends Repository<UserPreference, Long> {

    UserPreference save(UserPreference preference);

    Optional<UserPreference> findById(Long userId);
}
