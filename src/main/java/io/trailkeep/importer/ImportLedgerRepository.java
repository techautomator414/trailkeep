// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** The import ledger (design §8.2): insert-only, read by key. Not case-rooted: it holds keys and ids, no content. */
interface ImportLedgerRepository extends Repository<ImportLedgerEntry, Long> {

    ImportLedgerEntry save(ImportLedgerEntry entry);

    /** Which of these keys of a source's entity a load already wrote, in any run of that source. */
    @Query("select l.legacyKey from ImportLedgerEntry l where l.sourceId = :sourceId and l.entity = :entity"
            + " and l.legacyKey in :keys")
    List<String> loaded(String sourceId, ImportIssue.Subject entity, Collection<String> keys);
}
