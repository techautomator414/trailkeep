// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** The import ledger (design §8.2): insert-only, read by key. Not case-rooted: it holds keys and ids, no content. */
interface ImportLedgerRepository extends Repository<ImportLedgerEntry, Long> {

    ImportLedgerEntry save(ImportLedgerEntry entry);

    /** Which of these keys of a source's entity a load already wrote, in any run of that source. */
    @Query("select l.legacyKey from ImportLedgerEntry l where l.sourceId = :sourceId and l.entity = :entity"
            + " and l.legacyKey in :keys")
    List<String> loaded(String sourceId, ImportIssue.Subject entity, Collection<String> keys);

    /** The ledger rows of these keys of a source's entity, for a delta's dry run (design §3.1). */
    List<ImportLedgerEntry> findBySourceIdAndEntityAndLegacyKeyIn(
            String sourceId, ImportIssue.Subject entity, Collection<String> legacyKeys);

    /** The first ledger rows of a source's entity that runs other than this one wrote, in key order. */
    List<ImportLedgerEntry> findBySourceIdAndEntityAndRunIdNotOrderByLegacyKeyAsc(
            String sourceId, ImportIssue.Subject entity, long runId, Limit limit);

    /** The next ledger rows of a source's entity that runs other than this one wrote, in key order, after a key. */
    List<ImportLedgerEntry> findBySourceIdAndEntityAndRunIdNotAndLegacyKeyGreaterThanOrderByLegacyKeyAsc(
            String sourceId, ImportIssue.Subject entity, long runId, String after, Limit limit);

    /** The next rows one run's load wrote, in id order, after an id; {@code reconcile} reads them a page at a time. */
    List<ImportLedgerEntry> findByRunIdAndIdGreaterThanOrderByIdAsc(long runId, long after, Limit limit);

    /** How many rows a load wrote for a source, in any run. */
    long countBySourceId(String sourceId);

    /** How many rows of a source were loaded under another mapping than this one. */
    long countBySourceIdAndMappingHashNot(String sourceId, String mappingHash);
}
