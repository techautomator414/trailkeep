// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import org.springframework.data.repository.Repository;

/** A run's issues. Not case-rooted: an issue is a code, a number and a keyed hash. */
interface ImportIssueRepository extends Repository<ImportIssue, Long> {

    ImportIssue save(ImportIssue issue);

    long countByRunIdAndSeverity(long runId, ImportIssue.Severity severity);
}
