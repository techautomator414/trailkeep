// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Teams. Not case-rooted; module-internal. */
interface TeamRepository extends Repository<Team, Long> {

    Team save(Team team);

    /** Writes pending changes now, so a unique-name violation surfaces inside the service's transaction. */
    void flush();

    Optional<Team> findById(Long id);

    Optional<Team> findByName(String name);

    List<Team> findByIdIn(Collection<Long> ids);

    List<Team> findAllByOrderByNameAsc();
}
