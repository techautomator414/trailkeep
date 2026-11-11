// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the {@code status} command reports about a run (design §2.1): only codes, counts and instants, so it may be
 * logged.
 *
 * @param runId the run
 * @param kind what it does
 * @param sourceId the mapping's {@code source.id}
 * @param status where it is in its lifecycle
 * @param approved whether a Compliance Admin has signed it off
 * @param scanMode the scanning mode recorded at its dry run, or {@code null} before one
 * @param rowsStaged rows extracted into staging
 * @param rowsLoaded rows loaded into Trailkeep
 * @param issues its issues per severity; a severity with none is absent
 * @param stagingExpiresAt when its staging expires, or {@code null} before an extract
 * @param stagingExpired whether that time has passed and its staging has not been purged yet
 */
record ImportStatus(
        long runId,
        ImportRun.Kind kind,
        String sourceId,
        ImportRun.Status status,
        boolean approved,
        ImportRun.@Nullable ScanMode scanMode,
        long rowsStaged,
        long rowsLoaded,
        Map<ImportIssue.Severity, Long> issues,
        @Nullable Instant stagingExpiresAt,
        boolean stagingExpired) {}
