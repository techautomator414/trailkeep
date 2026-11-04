// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.util.List;
import org.springframework.data.repository.Repository;

/** Global roles. Not case-rooted; module-internal. Insert and delete only, matching the grants. */
interface AppUserGlobalRoleRepository extends Repository<AppUserGlobalRole, AppUserGlobalRoleId> {

    AppUserGlobalRole save(AppUserGlobalRole role);

    List<AppUserGlobalRole> findByIdUserId(Long userId);

    List<AppUserGlobalRole> findByIdRole(GlobalRole role);

    void delete(AppUserGlobalRole role);
}
