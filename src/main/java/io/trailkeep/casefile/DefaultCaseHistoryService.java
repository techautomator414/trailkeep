// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.audit.AuditChanges;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecord;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.audit.AuditTrailService;
import io.trailkeep.audit.AuditTrailService.AuditPage;
import io.trailkeep.audit.AuditTrailService.ImportedPage;
import io.trailkeep.audit.AuditTrailService.ImportedRecord;
import io.trailkeep.audit.ImportedHistoryService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.UserDirectoryService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a case's history through the person's scope: the case is found through the scope first (404 if unreadable),
 * the read of a restricted case is audited, and only then are its events paged. The stored old and new values are
 * redacted JSON; a redacted field arrives as an object holding an HMAC and a length, and leaves this class as "set" or
 * "changed" with neither. Owner and team ids are shown as names. The case's imported legacy history (WP-26 part x)
 * joins the audit events in one timeline through {@link HistoryTimeline}; its plain values are shown as stored and its
 * redacted ones, like audit's, only as "set" or "changed". Not read-only: the read may record an event.
 */
@Service
@CaseAccessPath
@Transactional
class DefaultCaseHistoryService implements CaseHistoryService {

    private static final TypeReference<Map<String, @Nullable Object>> VALUES = new TypeReference<>() {};
    private static final AuditPage NO_EVENTS = new AuditPage(List.of(), 0);
    private static final ImportedPage NO_IMPORTED = new ImportedPage(List.of(), 0);

    private final CaseRecordRepository cases;
    private final CaseScopeService scopes;
    private final AuditTrailService trail;
    private final AuditRecorder audit;
    private final UserDirectoryService directory;
    private final ZoneId zone;
    private final JsonMapper json = JsonMapper.builder().build();

    DefaultCaseHistoryService(
            CaseRecordRepository cases,
            CaseScopeService scopes,
            AuditTrailService trail,
            AuditRecorder audit,
            UserDirectoryService directory,
            AppProperties app) {
        this.cases = cases;
        this.scopes = scopes;
        this.trail = trail;
        this.audit = audit;
        this.directory = directory;
        this.zone = app.timezone();
    }

    /** An event with its old and new values read from JSON. */
    private record Parsed(
            AuditRecord event, Map<String, @Nullable Object> before, Map<String, @Nullable Object> after) {}

    @Override
    public CaseHistory history(Actor actor, String caseNumber, HistoryQuery query) {
        HistoryKind kind = query.kind();
        int page = query.page();
        CaseNumber number = CaseLoader.parse(caseNumber);
        CaseScope scope = scopes.forUser(actor.permissions());
        CaseRecord record =
                cases.findOne(scope, number).orElseThrow(() -> new CaseException(Kind.NOT_FOUND, "case not found"));
        // The history is a read of the case: a restricted one records it, and the record is then the newest entry.
        RestrictedReadAudit.record(audit, actor, record, AuditChanges.none());
        long caseId = Objects.requireNonNull(record.getId());
        int offset = (page - 1) * PAGE_SIZE;
        Set<String> actions = kind.actions();
        AuditPage found = kind.audited() ? trail.pageForCase(scope, caseId, actions, offset, PAGE_SIZE) : NO_EVENTS;
        ImportedPage imported =
                kind.imported() ? trail.importedPageForCase(scope, caseId, offset, PAGE_SIZE) : NO_IMPORTED;
        long total = found.total() + imported.total();
        if (offset > 0 && offset < total && found.total() > 0 && imported.total() > 0) {
            // Both sources have events: find where this page starts in each, reading one event per step.
            HistoryTimeline.Start start = HistoryTimeline.start(
                    offset,
                    found.total(),
                    imported.total(),
                    index -> first(
                            trail.pageForCase(scope, caseId, actions, index, 1).records(), AuditRecord::at),
                    index -> first(
                            trail.importedPageForCase(scope, caseId, index, 1).records(), ImportedRecord::at));
            found = trail.pageForCase(scope, caseId, actions, start.audited(), PAGE_SIZE);
            imported = trail.importedPageForCase(scope, caseId, start.imported(), PAGE_SIZE);
        }
        List<Parsed> parsed = found.records().stream().map(this::parse).toList();
        Names names = names(parsed);
        int pages = (int) Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        return new CaseHistory(
                number,
                record.getTitle(),
                kind,
                page,
                pages,
                total,
                HistoryTimeline.merge(
                        parsed.stream().map(entry -> entry(entry, names)).toList(),
                        imported.records().stream().map(this::entry).toList(),
                        PAGE_SIZE));
    }

    /** The time of a one-event page; a page emptied since it was counted reads as the oldest. */
    private static <T> Instant first(List<T> records, Function<T, Instant> at) {
        return records.isEmpty() ? Instant.MIN : at.apply(records.getFirst());
    }

    private Entry entry(Parsed parsed, Names names) {
        AuditRecord event = parsed.event();
        return new Entry(
                event.at(),
                local(event.at()),
                event.actorName(),
                event.origin(),
                event.action(),
                changes(parsed, names),
                null);
    }

    /** An imported event: its stored plain values, or for a redacted one only "set" or "changed". */
    private Entry entry(ImportedRecord event) {
        String field = event.field();
        if (field == null) {
            field = event.kind() == ImportedHistoryService.Kind.NOTE ? "note" : "value";
        }
        List<Change> changes = List.of();
        if (event.beforeRedacted() || event.afterRedacted()) {
            changes =
                    List.of(new Change(field, null, null, event.beforeRedacted() ? Redaction.CHANGED : Redaction.SET));
        } else if (event.before() != null || event.after() != null) {
            changes = List.of(new Change(field, event.before(), event.after(), null));
        }
        return new Entry(
                event.at(),
                local(event.at()),
                event.who(),
                AuditOrigin.IMPORT,
                "LEGACY_" + event.kind().name(),
                changes,
                event.runId());
    }

    private LocalDateTime local(Instant at) {
        return LocalDateTime.ofInstant(at, zone).truncatedTo(ChronoUnit.MINUTES);
    }

    private static List<Change> changes(Parsed parsed, Names names) {
        Set<String> fields = new LinkedHashSet<>(parsed.before().keySet());
        fields.addAll(parsed.after().keySet());
        List<Change> changes = new ArrayList<>(fields.size());
        for (String field : fields) {
            Object before = parsed.before().get(field);
            Object after = parsed.after().get(field);
            if (hidden(before) || hidden(after)) {
                changes.add(new Change(field, null, null, before == null ? Redaction.SET : Redaction.CHANGED));
            } else {
                changes.add(new Change(field, names.text(field, before), names.text(field, after), null));
            }
        }
        return changes;
    }

    /** A redacted field is stored as an object holding its HMAC and length. */
    private static boolean hidden(@Nullable Object value) {
        return value instanceof Map<?, ?> digest && digest.containsKey("hmac");
    }

    private Parsed parse(AuditRecord event) {
        return new Parsed(
                event,
                values(event.changes().oldValuesJson()),
                values(event.changes().newValuesJson()));
    }

    private Map<String, @Nullable Object> values(@Nullable String text) {
        if (text == null) {
            return Map.of();
        }
        try {
            return json.readValue(text, VALUES);
        } catch (RuntimeException unreadable) {
            // A malformed value is shown as no change rather than failing the page.
            return Map.of();
        }
    }

    /** Owner and team names for the ids of one page, looked up in two batches. */
    private Names names(List<Parsed> parsed) {
        Set<Long> owners = new HashSet<>();
        Set<Long> teams = new HashSet<>();
        for (Parsed entry : parsed) {
            for (Map<String, @Nullable Object> values : List.of(entry.before(), entry.after())) {
                collect(values.get("ownerId"), owners);
                collect(values.get("teamId"), teams);
            }
        }
        Map<Long, String> ownerNames = directory.displayNames(owners);
        Map<Long, String> teamNames = new HashMap<>();
        directory.teams(teams).forEach((id, team) -> teamNames.put(id, team.name()));
        return new Names(ownerNames, teamNames);
    }

    private static void collect(@Nullable Object value, Set<Long> ids) {
        if (value instanceof Number id) {
            ids.add(id.longValue());
        }
    }

    /** Names for the ids a field may hold; every other value is shown as it was stored. */
    private record Names(Map<Long, String> owners, Map<Long, String> teams) {

        @Nullable
        String text(String field, @Nullable Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof Number id) {
                Map<Long, String> names = Map.of();
                if ("ownerId".equals(field)) {
                    names = owners;
                } else if ("teamId".equals(field)) {
                    names = teams;
                }
                return names.getOrDefault(id.longValue(), String.valueOf(value));
            }
            return String.valueOf(value);
        }
    }
}
