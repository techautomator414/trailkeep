// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import io.trailkeep.shared.access.CaseScope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Reads the audit trail through a {@link CaseScope} (ADR-0005). Events with no case (logins, admin changes, exports)
 * are visible only to an unrestricted scope ({@link CaseScope#system()} or {@link CaseScope#all()}).
 */
public interface AuditTrailService {

    /**
     * The most recent events the scope may see, newest first.
     *
     * @param scope the caller's scope
     * @param limit the maximum number of events, 1 to 500
     * @return the events
     */
    List<AuditRecord> recent(CaseScope scope, int limit);

    /**
     * The events of one case, newest first; empty if the scope may not read the case.
     *
     * @param scope the caller's scope
     * @param caseId the case id
     * @param limit the maximum number of events, 1 to 500
     * @return the events
     */
    List<AuditRecord> forCase(CaseScope scope, long caseId, int limit);

    /**
     * One page of the events of one case, newest first, for a history view: {@link #forCase} cannot skip, so it cannot
     * page. Empty, with a total of zero, if the scope may not read the case.
     *
     * @param scope the caller's scope
     * @param caseId the case id
     * @param actions the actions to keep, such as {@code COMMENT_ADDED}; empty keeps every action
     * @param offset how many of the newest matching events to skip
     * @param limit the page size, 1 to 500
     * @return the page and how many events match in all
     */
    AuditPage pageForCase(CaseScope scope, long caseId, Set<String> actions, int offset, int limit);

    /**
     * One page of a case's imported legacy history ({@code CASE_IMPORTED_EVENT}, WP-26 part x, design §7.5), newest
     * legacy time first, for the History tab. Empty, with a total of zero, if the scope may not read the case. A
     * redacted value leaves this module only as the fact that it is redacted: never its HMAC or its length.
     *
     * @param scope the caller's scope
     * @param caseId the case id
     * @param offset how many of the newest imported events to skip
     * @param limit the page size, 1 to 500
     * @return the page and how many imported events the case has in all
     */
    ImportedPage importedPageForCase(CaseScope scope, long caseId, int offset, int limit);

    /**
     * One imported legacy event, as the History tab may show it.
     *
     * @param at the legacy event time
     * @param who the legacy actor's name as recorded, or {@code null} if the source had none
     * @param kind what kind of change it was
     * @param field its canonical field, or {@code null} for an event about no field
     * @param before the value before as stored text, or {@code null} if there was none or it is redacted
     * @param after the value after as stored text, or {@code null} if there is none or it is redacted
     * @param beforeRedacted whether there was a value before that is stored redacted
     * @param afterRedacted whether there is a value after that is stored redacted
     * @param runId the import run that brought it in
     */
    record ImportedRecord(
            Instant at,
            @Nullable String who,
            ImportedHistoryService.Kind kind,
            @Nullable String field,
            @Nullable String before,
            @Nullable String after,
            boolean beforeRedacted,
            boolean afterRedacted,
            long runId) {}

    /**
     * One page of imported legacy events.
     *
     * @param records the events of the page, newest first
     * @param total how many imported events the case has in all
     */
    record ImportedPage(List<ImportedRecord> records, long total) {

        /**
         * Copies the list, so the record holds nothing mutable.
         *
         * @param records the events of the page
         * @param total how many imported events there are in all
         */
        public ImportedPage {
            records = List.copyOf(records);
        }
    }

    /**
     * An entity whose event still stands, and when that event happened; no payload, actor or request detail.
     *
     * @param entityId the entity's id, such as a user's {@code APP_USER.ID}
     * @param at when the standing event was recorded
     */
    record StandingEvent(long entityId, Instant at) {}

    /**
     * The entities of one type whose latest case-less event, among {@code action} and {@code clearedBy}, is {@code
     * action}, oldest first: for example the users whose last {@code ROLE_CONFLICT} has not been followed by a {@code
     * LOGIN} (WP-25). Latest is by time, then by id within the same instant. Case-less events only, so no scope; the
     * caller decides who may see the result.
     *
     * @param entityType the entity type, such as {@code USER}
     * @param action the action that stands until cleared
     * @param clearedBy the actions that clear it when they come later
     * @return the entities and the time of their standing event
     */
    List<StandingEvent> standing(String entityType, String action, Set<String> clearedBy);

    /**
     * When the latest case-less event with this action happened for one entity, for example a user's last {@code
     * TEST_MAIL_QUEUED} (WP-25). Only the time: no payload, actor or request detail. Case-less events only, so no
     * scope; the caller decides what the answer is used for.
     *
     * @param entityType the entity type, such as {@code USER}
     * @param entityId the entity's id
     * @param action the action
     * @return its latest time, or empty if it never happened
     */
    Optional<Instant> latest(String entityType, long entityId, String action);

    /**
     * The longest time range one {@link #search} may cover, so no search reads an unbounded part of the trail: 32 days,
     * room for a month of whole days in a zone with a daylight-saving hour.
     */
    Duration MAX_RANGE = Duration.ofDays(32);

    /**
     * What a search of the whole audit trail keeps (BUILD_PLAN §9 "audit export for Compliance Admins").
     *
     * @param from the first instant included
     * @param to the first instant excluded: after {@code from}, at most {@link #MAX_RANGE} later
     * @param actorName text the actor's recorded name contains, in any case; {@code null} for any actor
     * @param entityType the entity type, such as {@code USER}; {@code null} for any
     * @param action the action, such as {@code LOGIN}; {@code null} for any
     * @param caseId the one case whose events are kept; {@code null} for events of any case or of none
     */
    record AuditFilter(
            Instant from,
            Instant to,
            @Nullable String actorName,
            @Nullable String entityType,
            @Nullable String action,
            @Nullable Long caseId) {

        /**
         * Checks the time range.
         *
         * @param from the first instant included
         * @param to the first instant excluded
         * @param actorName text the actor's name contains
         * @param entityType the entity type
         * @param action the action
         * @param caseId the case
         */
        public AuditFilter {
            if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
                throw new IllegalArgumentException("the time range must be positive and at most " + MAX_RANGE);
            }
        }
    }

    /**
     * One page of the events a filter keeps, newest first, case-less events included. Only an unrestricted scope sees
     * anything (the security module grants one to a Compliance Admin); any other scope gets an empty page with a total
     * of zero. Values stay as stored: a redacted one is its HMAC and length, never text.
     *
     * @param scope the caller's scope
     * @param filter the time range and the other filters
     * @param offset how many of the newest matching events to skip
     * @param limit the page size, 1 to 500
     * @return the page and how many events match in all
     */
    AuditPage search(CaseScope scope, AuditFilter filter, int offset, int limit);

    /**
     * One page of audit events.
     *
     * @param records the events of the page, newest first
     * @param total how many events match in all, on every page
     */
    record AuditPage(List<AuditRecord> records, long total) {

        /**
         * Copies the list, so the record holds nothing mutable.
         *
         * @param records the events of the page
         * @param total how many events match in all
         */
        public AuditPage {
            records = List.copyOf(records);
        }
    }
}
