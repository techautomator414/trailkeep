// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.util.Collection;
import java.util.List;
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

    /**
     * How many of an entity's rows have an issue of a severity under a code not accepted as skipped; before the load,
     * only the extract and the dry run record row issues. An empty {@code accepted} excludes nothing.
     */
    @Query("select count(distinct i.keyHash) from ImportIssue i where i.runId = :runId and i.subject = :subject"
            + " and i.severity = :severity and i.code not in :accepted")
    long countFailedRows(
            long runId, ImportIssue.Subject subject, ImportIssue.Severity severity, Collection<String> accepted);

    /**
     * Those of these keyed hashes whose rows have an issue of a severity, at any stage but {@code reconcile}'s: the
     * load skips them, and {@code reconcile} counts them as failed. A {@code RECONCILE} issue only reports, so it never
     * changes what a later load does, nor hides a difference from the next reconcile (WP-26 part aa2b).
     */
    @Query("select distinct i.keyHash from ImportIssue i where i.runId = :runId and i.subject = :subject"
            + " and i.severity = :severity and i.keyHash in :keyHashes"
            + " and i.stage <> io.trailkeep.importer.ImportIssue.Stage.RECONCILE")
    List<String> keyHashesWith(
            long runId, ImportIssue.Subject subject, ImportIssue.Severity severity, Collection<String> keyHashes);

    /** A run's issues counted by entity, severity and code: each element is those three and the count. */
    @Query("select i.subject, i.severity, i.code, count(i) from ImportIssue i where i.runId = :runId"
            + " group by i.subject, i.severity, i.code")
    List<Object[]> countByCode(long runId);
}
