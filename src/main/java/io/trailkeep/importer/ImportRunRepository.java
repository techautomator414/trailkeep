// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.Repository;

/** Import runs. Not case-rooted: a run holds codes, hashes and counts, no case content. */
interface ImportRunRepository extends Repository<ImportRun, Long> {

    ImportRun save(ImportRun run);

    Optional<ImportRun> findById(Long id);

    /** The runs the staging TTL has caught up with (design §5). */
    List<ImportRun> findByStagingExpiresAtLessThanEqualAndStagingPurgedAtIsNull(Instant now);

    /**
     * A source's runs, locked for update in id order, so that loads of one source claim their runs in turn (design
     * §8.2). PostgreSQL needs UPDATE on one column for the lock, which the import user has; Oracle needs SELECT.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<ImportRun> findBySourceIdOrderByIdAsc(String sourceId);
}
