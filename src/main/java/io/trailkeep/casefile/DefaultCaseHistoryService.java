// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.audit.AuditChanges;
import io.trailkeep.audit.AuditRecord;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.audit.AuditTrailService;
import io.trailkeep.audit.AuditTrailService.AuditPage;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.UserDirectoryService;
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
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a case's history through the person's scope: the case is found through the scope first (404 if unreadable),
 * the read of a restricted case is audited, and only then are its events paged. The stored old and new values are
 * redacted JSON; a redacted field arrives as an object holding an HMAC and a length, and leaves this class as "set" or
 * "changed" with neither. Owner and team ids are shown as names. Not read-only: the read may record an event.
 */
@Service
@CaseAccessPath
@Transactional
class DefaultCaseHistoryService implements CaseHistoryService {

    private static final TypeReference<Map<String, @Nullable Object>> VALUES = new TypeReference<>() {};

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
        AuditPage found = trail.pageForCase(
                scope, Objects.requireNonNull(record.getId()), kind.actions(), (page - 1) * PAGE_SIZE, PAGE_SIZE);
        List<Parsed> parsed = found.records().stream().map(this::parse).toList();
        Names names = names(parsed);
        int pages = (int) Math.max(1, (found.total() + PAGE_SIZE - 1) / PAGE_SIZE);
        return new CaseHistory(
                number,
                record.getTitle(),
                kind,
                page,
                pages,
                found.total(),
                parsed.stream().map(entry -> entry(entry, names)).toList());
    }

    private Entry entry(Parsed parsed, Names names) {
        AuditRecord event = parsed.event();
        return new Entry(
                event.at(),
                LocalDateTime.ofInstant(event.at(), zone).truncatedTo(ChronoUnit.MINUTES),
                event.actorName(),
                event.origin(),
                event.action(),
                changes(parsed, names));
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
