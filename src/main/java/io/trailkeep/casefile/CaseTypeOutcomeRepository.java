// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Case-type outcomes; replaced as a whole. Not case-rooted; module-internal. */
interface CaseTypeOutcomeRepository extends Repository<CaseTypeOutcome, CaseTypeOutcome.Key> {

    CaseTypeOutcome save(CaseTypeOutcome outcome);

    List<CaseTypeOutcome> findByIdCaseTypeIdOrderBySortOrderAsc(Long caseTypeId);

    /**
     * Bulk delete, executed at once, so re-inserting the same outcome in this transaction cannot collide. Flushes
     * pending changes first and clears the persistence context after, so no stale outcome entity is merged back.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from CaseTypeOutcome o where o.id.caseTypeId = :caseTypeId")
    void deleteAllOfType(@Param("caseTypeId") Long caseTypeId);
}
