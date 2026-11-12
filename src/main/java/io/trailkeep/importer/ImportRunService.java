// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.Paged;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The importer's API for other modules: the System screen (WP-25 part d) and the Import runs screen (WP-26 part s). It
 * reads {@code IMPORT_RUN}, {@code IMPORT_ISSUE} and {@code IMPORT_RUN_SUMMARY}, on which the runtime user holds
 * SELECT, so it works in the web application as well as in the {@code importer} profile. It returns codes, times and
 * counts only: never a source id, a mapping, a key hash or anything taken from legacy data.
 */
public interface ImportRunService {

    /**
     * One run, as other modules may show it.
     *
     * @param id the run's id
     * @param kind {@code IMPORT} or {@code RELINK}
     * @param status where it is: {@code REQUESTED}, {@code VALIDATED}, {@code EXTRACTED}, {@code DRY_RUN}, {@code
     *     APPROVED} (also a {@code DRY_RUN} a Compliance Admin has signed off), {@code LOADING}, {@code LOADED},
     *     {@code RECONCILED} or {@code FAILED}
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
     * How many of a run's issues share an entity, a severity and a code: one class of issue.
     *
     * @param entity {@code MAPPING}, {@code PERSON}, {@code CASE}, {@code COMMENT}, {@code ATTACHMENT} or {@code EVENT}
     * @param severity {@code FATAL}, {@code ERROR} or {@code WARNING}
     * @param code the issue code
     * @param count how many, from every stage so far
     */
    record IssueCount(String entity, String severity, String code, long count) {}

    /**
     * One figure of the last completed dry run ({@code IMPORT_RUN_SUMMARY}, design §3 R8).
     *
     * @param metric what is counted, such as {@code CASES} or {@code PEOPLE_MATCHED}
     * @param dimension what it is broken down by (an entity, a status, a role, a code or an SLA state), or {@code
     *     null} for a total; for a figure per team it is the team id, also given as {@code teamId}
     * @param teamId the Trailkeep team the figure is for, or {@code null} if it is not per team
     * @param value the number
     */
    record Figure(
            String metric,
            @Nullable String dimension,
            @Nullable Long teamId,
            long value) {}

    /**
     * One run with everything the Import runs screen shows about it.
     *
     * @param summary the run as the list shows it
     * @param requestedBy the {@code APP_USER} id of whoever requested it
     * @param approvedBy the {@code APP_USER} id of the Compliance Admin who signed it off, or {@code null}
     * @param approvedAt when it was signed off, or {@code null}
     * @param scanMode the scanning mode recorded by its last completed dry run ({@code CLAMD} or {@code EXTERNAL}), or
     *     {@code null} before one
     * @param rowsStaged rows extracted into staging
     * @param issues its issues per class: fatal first, then by entity and code
     * @param figures its dry-run figures by metric and dimension, without the issue counts and the forecast day
     * @param forecastDay the day the SLA forecast is for, or {@code null} without one
     * @param acknowledged the WARNING classes (codes) acknowledged at its sign-off, empty before one
     * @param version the version a sign-off must name, so that a run changed since the page was loaded is refused
     * @param signable whether it may be signed off: its last dry run completed, with no FATAL issue, its staging is
     *     still there, and nobody has signed it off yet
     */
    record RunDetail(
            RunSummary summary,
            long requestedBy,
            @Nullable Long approvedBy,
            @Nullable Instant approvedAt,
            @Nullable String scanMode,
            long rowsStaged,
            List<IssueCount> issues,
            List<Figure> figures,
            @Nullable LocalDate forecastDay,
            List<String> acknowledged,
            int version,
            boolean signable) {

        /**
         * Copies the lists, so the record holds nothing mutable.
         *
         * @param summary the run as the list shows it
         * @param requestedBy who requested it
         * @param approvedBy who signed it off, or {@code null}
         * @param approvedAt when, or {@code null}
         * @param scanMode the recorded scanning mode, or {@code null}
         * @param rowsStaged rows extracted into staging
         * @param issues its issues per class
         * @param figures its dry-run figures
         * @param forecastDay the SLA forecast day, or {@code null}
         * @param acknowledged the WARNING classes acknowledged
         * @param version the version
         * @param signable whether it may be signed off
         */
        public RunDetail {
            issues = List.copyOf(issues);
            figures = List.copyOf(figures);
            acknowledged = List.copyOf(acknowledged);
        }

        /**
         * The WARNING classes a sign-off must acknowledge, one per code, in code order.
         *
         * @return the codes
         */
        public List<String> warnings() {
            return issues.stream()
                    .filter(issue -> "WARNING".equals(issue.severity()))
                    .map(IssueCount::code)
                    .distinct()
                    .sorted()
                    .toList();
        }
    }

    /**
     * The most recently requested run, by request time and then by id.
     *
     * @return the run, or empty if none was ever requested
     */
    Optional<RunSummary> latest();

    /**
     * One page of runs, most recently requested first, 20 to a page.
     *
     * @param actor who asks: a System Admin or a Compliance Admin
     * @param page the page, from 1; a page past the last shows the last
     * @return the page
     * @throws ImportRunException {@code FORBIDDEN} for anyone else
     */
    Paged<RunSummary> runs(Actor actor, int page);

    /**
     * One run.
     *
     * @param actor who asks: a System Admin or a Compliance Admin
     * @param id the run's id
     * @return the run
     * @throws ImportRunException {@code FORBIDDEN} for anyone else, then {@code NOT_FOUND} for an unknown run
     */
    RunDetail run(Actor actor, long id);

    /**
     * Refuses anyone who may not use the Import runs screen.
     *
     * @param actor who asks
     * @throws ImportRunException {@code FORBIDDEN} for anyone but a System Admin or a Compliance Admin
     */
    void checkAccess(Actor actor);

    /**
     * Whether the actor may sign runs off.
     *
     * @param actor who asks
     * @return true for a Compliance Admin
     */
    boolean signsOff(Actor actor);

    /**
     * Requests an import run for a source (design §3 step 0): the run starts {@code REQUESTED}, the importer Job's
     * {@code validate-mapping} takes it from there. Audited as {@code IMPORT_RUN_REQUESTED}.
     *
     * @param actor who requests it: a System Admin or a Compliance Admin
     * @param sourceId the mapping's {@code source.id}: 1 to 16 lowercase letters, digits or hyphens
     * @return the new run's id
     * @throws ImportRunException {@code FORBIDDEN} for anyone else, {@code INVALID} for a malformed source id
     */
    long request(Actor actor, String sourceId);

    /**
     * Signs a run off (design §3 step 4): any Compliance Admin, the requester included, approves the run as the page
     * showed it, having acknowledged every WARNING class. Everything is checked again here, never taken from the
     * page. Stored as {@code APPROVED_BY}, {@code APPROVED_AT} and {@code ACKNOWLEDGED_WARNINGS} and audited as {@code
     * IMPORT_RUN_SIGNED_OFF}, with the recorded scanning mode.
     *
     * @param actor who signs it off
     * @param id the run
     * @param version the version the page showed
     * @param acknowledged the WARNING classes (codes) acknowledged
     * @throws ImportRunException {@code FORBIDDEN} for anyone but a Compliance Admin, then {@code NOT_FOUND} for an
     *     unknown run, then {@code CONFLICT} for a run changed since that version or not signable (its last dry run
     *     did not complete, it has a FATAL issue, its staging is gone or it is signed off already), then {@code
     *     INVALID} when a WARNING class is not acknowledged
     */
    void signOff(Actor actor, long id, int version, Set<String> acknowledged);
}
