// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** A run's aggregates (design §3 R8): numbers only, rewritten by each dry run. */
interface ImportRunSummaryRepository extends Repository<ImportRunSummary, Long> {

    ImportRunSummary save(ImportRunSummary figure);

    /** A dry run that runs again replaces the run's figures. */
    @Modifying
    @Query("delete from ImportRunSummary s where s.runId = :runId")
    int deleteByRunId(long runId);
}
