// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Saved views, always looked up with their owner. Not case-rooted; module-internal. */
interface SavedViewRepository extends Repository<SavedView, Long> {

    SavedView save(SavedView view);

    void delete(SavedView view);

    Optional<SavedView> findByIdAndUserId(Long id, long userId);

    boolean existsByUserIdAndName(long userId, String name);

    List<SavedView> findByUserIdOrderByNameAscIdAsc(long userId);
}
