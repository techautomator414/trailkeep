// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Teams. Not case-rooted; module-internal. */
interface TeamRepository extends Repository<Team, Long> {

    Team save(Team team);

    Optional<Team> findById(Long id);

    Optional<Team> findByName(String name);
}
