// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** A run's issues. Not case-rooted: an issue is a code, a number and a keyed hash. */
interface ImportIssueRepository extends Repository<ImportIssue, Long> {

    ImportIssue save(ImportIssue issue);

    long countByRunIdAndSeverity(long runId, ImportIssue.Severity severity);

    /** A stage that runs again replaces its own issues. */
    @Modifying
    @Query("delete from ImportIssue i where i.runId = :runId and i.stage = :stage")
    int deleteByRunIdAndStage(long runId, ImportIssue.Stage stage);

    /** An entity extracted again replaces that entity's issues of the stage. */
    @Modifying
    @Query("delete from ImportIssue i where i.runId = :runId and i.stage = :stage and i.subject = :subject")
    int deleteByRunIdAndStageAndSubject(long runId, ImportIssue.Stage stage, ImportIssue.Subject subject);
}
