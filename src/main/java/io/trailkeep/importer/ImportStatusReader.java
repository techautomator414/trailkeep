// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reads a run's {@link ImportStatus} for the {@code status} command. */
@Component
class ImportStatusReader {

    private final ImportRunRepository runs;
    private final ImportIssueRepository issues;

    ImportStatusReader(ImportRunRepository runs, ImportIssueRepository issues) {
        this.runs = runs;
        this.issues = issues;
    }

    /**
     * A run's status as of {@code now}.
     *
     * @param runId the run
     * @param now the instant the staging expiry is judged against
     * @return its status
     * @throws IllegalArgumentException if there is no such run
     */
    @Transactional(readOnly = true)
    ImportStatus status(long runId, Instant now) {
        ImportRun run = runs.findById(runId).orElseThrow(() -> new IllegalArgumentException("No import run " + runId));
        Map<ImportIssue.Severity, Long> counts = new EnumMap<>(ImportIssue.Severity.class);
        for (ImportIssue.Severity severity : ImportIssue.Severity.values()) {
            long count = issues.countByRunIdAndSeverity(runId, severity);
            if (count > 0) {
                counts.put(severity, count);
            }
        }
        Instant expiresAt = run.getStagingExpiresAt();
        boolean expired = expiresAt != null && run.getStagingPurgedAt() == null && !expiresAt.isAfter(now);
        return new ImportStatus(
                run.getId(),
                run.getKind(),
                run.getSourceId(),
                run.getStatus(),
                run.isApproved(),
                run.getScanMode(),
                run.getRowsStaged(),
                run.getRowsLoaded(),
                Map.copyOf(counts),
                expiresAt,
                expired);
    }
}
