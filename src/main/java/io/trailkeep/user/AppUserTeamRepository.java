// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.util.List;
import org.springframework.data.repository.Repository;

/** Team memberships. Not case-rooted; module-internal. */
interface AppUserTeamRepository extends Repository<AppUserTeam, AppUserTeamId> {

    AppUserTeam save(AppUserTeam membership);

    List<AppUserTeam> findByIdUserId(Long userId);

    List<AppUserTeam> findByIdTeamId(Long teamId);

    void delete(AppUserTeam membership);
}
