// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseNumber;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The History page of a case (BUILD_PLAN §4.3): its audit events and its imported legacy history (WP-26 part x,
 * design §7.5) in one timeline, newest first, 50 to a page, read through the person's scope. Reading history is itself
 * a read of the case, so for a restricted case it records {@code VIEW_RESTRICTED} like the case page does.
 */
public interface CaseHistoryService {

    /** Events to a page. */
    int PAGE_SIZE = 50;

    /**
     * One page of a case's history.
     *
     * @param actor the actor
     * @param caseNumber the case number as it appears in the URL
     * @param query the kind of event to keep and the page, from 1; a page past the last is empty
     * @return the page; {@code NOT_FOUND} if the number is malformed, the case is absent, or the actor may not read it
     */
    CaseHistory history(Actor actor, String caseNumber, HistoryQuery query);

    /**
     * A page of history.
     *
     * @param number the case
     * @param title the case's title
     * @param kind the kind shown
     * @param page the page shown
     * @param pages how many pages there are, at least 1
     * @param total how many events match the kind
     * @param entries the events of the page, newest first
     */
    record CaseHistory(
            CaseNumber number, String title, HistoryKind kind, int page, int pages, long total, List<Entry> entries) {

        /**
         * Copies the list, so the record holds nothing mutable.
         *
         * @param number the case
         * @param title the case's title
         * @param kind the kind shown
         * @param page the page shown
         * @param pages how many pages there are
         * @param total how many events match the kind
         * @param entries the events of the page
         */
        public CaseHistory {
            entries = List.copyOf(entries);
        }
    }

    /**
     * One event: an audit event, or an imported legacy one (WP-26 part x), which carries its import run.
     *
     * @param at when it happened
     * @param local the same moment in {@code app.timezone}, to the minute
     * @param who the person's name at the time, or {@code null} for the system (for an imported event: the legacy
     *     actor's name as recorded, or {@code null} if the source had none)
     * @param origin what initiated it; {@code IMPORT} for an imported event
     * @param action the audit action, such as {@code CASE_UPDATED}, or {@code LEGACY_} and the kind of an imported
     *     event, such as {@code LEGACY_STATUS_CHANGE}
     * @param changes the fields it changed
     * @param importRun the import run of an imported event, or {@code null} for an audit event
     */
    record Entry(
            Instant at,
            LocalDateTime local,
            @Nullable String who,
            AuditOrigin origin,
            String action,
            List<Change> changes,
            @Nullable Long importRun) {

        /**
         * Copies the list, so the record holds nothing mutable.
         *
         * @param at when it happened
         * @param local the same moment in the application time zone
         * @param who the person's name at the time, or {@code null}
         * @param origin what initiated it
         * @param action the audit action, or {@code LEGACY_} and the kind
         * @param changes the fields it changed
         * @param importRun the import run of an imported event, or {@code null}
         */
        public Entry {
            changes = List.copyOf(changes);
        }
    }

    /**
     * One field of an event. A redacted field (subject, description, a release reason, a file name) never carries
     * its value, its digest or its length: only that it was {@link Redaction#SET} or {@link Redaction#CHANGED}.
     *
     * @param field the field's name
     * @param before the old value as text, or {@code null} if there was none or it is redacted
     * @param after the new value as text, or {@code null} if there is none or it is redacted
     * @param redaction how a redacted field is described, or {@code null} for a field shown as it is
     */
    record Change(
            String field,
            @Nullable String before,
            @Nullable String after,
            @Nullable Redaction redaction) {}

    /** What is said of a field whose value is not shown. */
    enum Redaction {
        /** It had no value and now has one. */
        SET,
        /** It had a value and has another, or none. */
        CHANGED
    }
}
