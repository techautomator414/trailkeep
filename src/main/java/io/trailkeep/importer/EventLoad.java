// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.audit.ImportedHistoryService;
import io.trailkeep.audit.ImportedHistoryService.ImportedEvent;
import io.trailkeep.audit.ImportedHistoryService.Kind;
import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.ImportIssue.Subject;
import io.trailkeep.importer.staging.StagedEvent;
import io.trailkeep.user.ImportPeopleDirectory.PersonMatch;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The legacy history part of one load (WP-26 part w, design §7.5, §8.2): reads the staged events of a page of cases,
 * leaving out every event with an ERROR from the extract or the dry run, and writes a case's events into {@code
 * CASE_IMPORTED_EVENT} through {@code audit}'s import API, in the case's transaction, each with its import ledger row.
 *
 * <ul>
 *   <li>The field is the canonical one the mapping placed the legacy field on ({@code event.fields.field}, typically a
 *       lookup with {@code other} as its default). Only the values of a plain field ({@link
 *       ImportedHistoryService#storesAsText}) are stored as text, cut to {@link ImportedHistoryService#MAX_VALUE}
 *       characters, which the dry run reported as {@code VALUE_TRUNCATED}; every other value goes to the service
 *       whole, which keeps only its HMAC and length.
 *   <li>The actor is a name snapshot: the name the event recorded, else the legacy person's staged name, else the
 *       matched user's name, cut to {@link ImportedHistoryService#MAX_WHO}; none if the source names nobody.
 *   <li>Each event is checked again at the load's moment ({@link ChildRules#event}); an ERROR keeps its whole case
 *       out as an {@code EVENT_<code>} {@code LOAD} issue, which the next load tries again.
 * </ul>
 */
final class EventLoad {

    /** A case's events in the order they happened, then by key. */
    private static final Comparator<StagedEvent> HAPPENED = Comparator.comparing(
                    StagedEvent::getEventAt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()))
            .thenComparing(row -> row.getKey().legacyKey());

    private final CommentLoad.Context context;
    private final ImportedHistoryService history;
    private final ChildRules rules;

    /**
     * The history part of a load.
     *
     * @param context what the load's children share
     * @param history {@code audit}'s import API
     * @param rules the event rules at the load's moment
     */
    EventLoad(CommentLoad.Context context, ImportedHistoryService history, ChildRules rules) {
        this.context = context;
        this.history = history;
        this.rules = rules;
    }

    /**
     * The staged events of a page's cases to load, by case key, in the order they happened.
     *
     * @param caseKeys the cases' canonical keys
     * @return each case's events; a case without any is absent
     */
    Map<String, List<StagedEvent>> staged(List<String> caseKeys) {
        Map<String, List<StagedEvent>> byCase =
                CommentLoad.loadable(context, StagedEvent.class, Subject.EVENT, StagedEvent::getCaseKey, caseKeys);
        byCase.values().forEach(rows -> rows.sort(HAPPENED));
        return byCase;
    }

    /**
     * Why a case's events keep it out now, before its transaction.
     *
     * @param staged the case's events
     * @return {@code EVENT_} and the first ERROR's code, or {@code null} when every event may be written
     */
    @Nullable
    String refusal(List<StagedEvent> staged) {
        for (StagedEvent row : staged) {
            for (ChildRules.Finding finding : rules.event(row, true)) {
                if (finding.severity() == Severity.ERROR) {
                    return "EVENT_" + finding.code();
                }
            }
        }
        return null;
    }

    /**
     * Writes a case's events in its transaction, each with its ledger row.
     *
     * @param created the case
     * @param staged its events, checked
     * @param people the load's matched people
     */
    void write(CaseImportService.Created created, List<StagedEvent> staged, PeopleMatcher people) {
        for (StagedEvent row : staged) {
            Kind kind = Kind.valueOf(Objects.requireNonNull(row.getKind(), "checked"));
            boolean plain = ImportedHistoryService.storesAsText(kind, row.getFieldName());
            long id = history.record(new ImportedEvent(
                    created.caseId(),
                    Objects.requireNonNull(row.getEventAt(), "checked"),
                    who(row, people),
                    kind,
                    row.getFieldName(),
                    plain ? cut(row.getOldValue(), ImportedHistoryService.MAX_VALUE) : row.getOldValue(),
                    plain ? cut(row.getNewValue(), ImportedHistoryService.MAX_VALUE) : row.getNewValue(),
                    context.run().runId()));
            context.ledger()
                    .save(ImportLedgerEntry.ofChild(
                                    context.run(),
                                    Subject.EVENT,
                                    row.getKey().legacyKey(),
                                    row.getRowHash(),
                                    created.caseId(),
                                    id,
                                    context.now())
                            .written(
                                    WrittenHash.of(context.hashKey(), List.of(id), history.writtenEvents(List.of(id))),
                                    null,
                                    List.of()));
        }
    }

    /**
     * How long the longer value of an event is when the history stores it as text and must cut it.
     *
     * @param row the event, with a valid kind
     * @return that length, or 0 when nothing is cut
     */
    static int truncated(StagedEvent row) {
        Kind kind = Kind.valueOf(Objects.requireNonNull(row.getKind(), "checked"));
        if (!ImportedHistoryService.storesAsText(kind, row.getFieldName())) {
            return 0;
        }
        int longest = Math.max(length(row.getOldValue()), length(row.getNewValue()));
        return longest > ImportedHistoryService.MAX_VALUE ? longest : 0;
    }

    private static @Nullable String who(StagedEvent row, PeopleMatcher people) {
        String name = row.getWhoName();
        if (name == null || name.isBlank()) {
            name = people.name(row.getWhoKey());
        }
        if (name == null || name.isBlank()) {
            PersonMatch match = people.match(row.getWhoKey());
            name = match == null ? null : match.displayName();
        }
        return name == null || name.isBlank() ? null : cut(name, ImportedHistoryService.MAX_WHO);
    }

    /** A value cut to at most {@code max} characters, never between the two halves of a surrogate pair. */
    private static @Nullable String cut(@Nullable String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end);
    }

    private static int length(@Nullable String value) {
        return value == null ? 0 : value.length();
    }
}
