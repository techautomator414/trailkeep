// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * The open cases waiting for their legacy owner (design §7.3): written by the load, read and deleted by {@code
 * relink-owners}. Not case-rooted: it holds ids and keyed hashes, no case content.
 */
interface ImportOwnerPendingRepository extends Repository<ImportOwnerPending, Long> {

    ImportOwnerPending save(ImportOwnerPending pending);

    /** The next pending owners of every load of a source, in id order, after an id. */
    @Query("select p from ImportOwnerPending p where p.id > :after and p.runId in"
            + " (select r.id from ImportRun r where r.sourceId = :sourceId) order by p.id")
    List<ImportOwnerPending> page(String sourceId, long after, Limit limit);

    /** Removes a pending owner whose case was relinked or can no longer be. */
    @Modifying
    @Query("delete from ImportOwnerPending p where p.id = :id")
    int remove(long id);
}
