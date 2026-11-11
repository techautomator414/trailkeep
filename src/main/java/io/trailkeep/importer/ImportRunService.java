// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The importer's read API for other modules (first used by the System screen, WP-25 part d). It reads {@code
 * IMPORT_RUN}, on which the runtime user holds SELECT, so it works in the web application as well as in the
 * {@code importer} profile. It returns codes, times and counts only: never a source id, a mapping or anything taken
 * from legacy data. The Import runs screen (WP-26 part s) extends this interface rather than adding another.
 */
public interface ImportRunService {

    /**
     * One run, as other modules may show it.
     *
     * @param id the run's id
     * @param kind {@code IMPORT} or {@code RELINK}
     * @param status where it is: {@code REQUESTED}, {@code VALIDATED}, {@code EXTRACTED}, {@code DRY_RUN}, {@code
     *     APPROVED}, {@code LOADING}, {@code LOADED}, {@code RECONCILED} or {@code FAILED}
     * @param requestedAt when it was requested
     * @param updatedAt when its status or sign-off last changed
     * @param rowsLoaded the rows loaded so far, once loading has started; {@code null} before that
     */
    record RunSummary(
            long id,
            String kind,
            String status,
            Instant requestedAt,
            Instant updatedAt,
            @Nullable Long rowsLoaded) {}

    /**
     * The most recently requested run, by request time and then by id.
     *
     * @return the run, or empty if none was ever requested
     */
    Optional<RunSummary> latest();
}
