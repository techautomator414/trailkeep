// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import io.trailkeep.casefile.CaseService.Actor;
import org.jspecify.annotations.Nullable;

/**
 * The reports as CSV (BUILD_PLAN §4.5, WP-22 part b): exactly the lines and the total row of {@link ReportService}'s
 * reports, under the same role check, scope and period rules, written by the one CSV writer every export uses
 * ({@link io.trailkeep.shared.csv.CsvFormat}: quoted cells, formulas defused, a byte order mark first). Every export
 * is audited ({@code REPORT_EXPORTED}) with the report, its period and its line count, like the cases list's export;
 * a refused or invalid request is not.
 */
public interface ReportExportService {

    /**
     * A CSV file.
     *
     * @param fileName the name to save it under, letters, digits, dashes and the {@code .csv} suffix only
     * @param content the file, a byte order mark first
     */
    record Csv(String fileName, String content) {}

    /**
     * The aging report as CSV, as of today.
     *
     * @param actor the actor
     * @return the file
     */
    Csv aging(Actor actor);

    /**
     * The SLA-compliance report as CSV; the period as {@link ReportService#compliance}.
     *
     * @param actor the actor
     * @param from the first day, or blank
     * @param to the last day, or blank
     * @return the file
     */
    Csv compliance(Actor actor, @Nullable String from, @Nullable String to);
}
