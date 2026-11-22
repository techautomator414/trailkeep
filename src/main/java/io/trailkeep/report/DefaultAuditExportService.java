// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.trailkeep.audit.AuditChanges;
import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecord;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.audit.AuditTrailService;
import io.trailkeep.audit.AuditTrailService.AuditFilter;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.shared.csv.CsvFormat;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Counts, audits and streams an audit export, as the cases list's export does: the count and the audit event are one
 * transaction, and the rows are read when the response body is written, {@value #PAGE_SIZE} a statement inside one
 * read-only transaction. The filter's end is pinned to the moment of the count, so pages cannot shift under new events.
 */
@Service
class DefaultAuditExportService implements AuditExportService {

    /** Rows read per statement while streaming. */
    static final int PAGE_SIZE = 500;

    private static final List<String> HEADER = List.of(
            "Time (UTC)",
            "Actor",
            "Origin",
            "Entity type",
            "Entity id",
            "Action",
            "Case number",
            "Changes",
            "Request id");

    private final AuditTrailService trail;
    private final AuditRecorder audit;
    private final CaseService cases;
    private final InstantSource clock;
    private final int maxRows;
    private final TransactionTemplate readOnly;

    DefaultAuditExportService(
            AuditTrailService trail,
            AuditRecorder audit,
            CaseService cases,
            InstantSource clock,
            @Value("${app.export.max-rows:10000}") int maxRows,
            PlatformTransactionManager transactionManager) {
        this.trail = trail;
        this.audit = audit;
        this.cases = cases;
        this.clock = clock;
        this.maxRows = maxRows;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /**
     * What the audit trail keeps of an export.
     *
     * @param rows how many events it holds
     * @param query the screen's query string
     */
    record Exported(long rows, String query) {}

    @Override
    @Transactional
    public Prepared export(Actor actor, CaseScope scope, AuditFilter requested, String query) {
        // Milliseconds: a later event's stored time, at any precision, is never before this end.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        AuditFilter filter = requested.to().isAfter(now) && now.isAfter(requested.from())
                ? new AuditFilter(
                        requested.from(),
                        now,
                        requested.actorName(),
                        requested.entityType(),
                        requested.action(),
                        requested.caseId())
                : requested;
        long rows = trail.search(scope, filter, 0, 1).total();
        if (rows > maxRows) {
            return new TooMany(rows, maxRows);
        }
        audit.record(new AuditEntry(
                actor.permissions().userId(),
                actor.displayName(),
                AuditOrigin.UI,
                null,
                "AUDIT_TRAIL",
                null,
                "AUDIT_EXPORTED",
                audit.diff(null, new Exported(rows, query))));
        return new Streamed(rows, actor, scope, filter);
    }

    /** The export, read and written when the response body is. */
    private final class Streamed implements Ready {

        private final long rows;
        private final Actor actor;
        private final CaseScope scope;
        private final AuditFilter filter;

        Streamed(long rows, Actor actor, CaseScope scope, AuditFilter filter) {
            this.rows = rows;
            this.actor = actor;
            this.scope = scope;
            this.filter = filter;
        }

        @Override
        public long rows() {
            return rows;
        }

        @Override
        public void writeTo(OutputStream out) throws IOException {
            Writer writer = new BufferedWriter(new OutputStreamWriter(out, UTF_8));
            writer.write(CsvFormat.BOM);
            writer.write(CsvFormat.line(HEADER));
            try {
                readOnly.executeWithoutResult(status -> {
                    for (int offset = 0; offset < maxRows; offset += PAGE_SIZE) {
                        List<AuditRecord> page =
                                trail.search(scope, filter, offset, PAGE_SIZE).records();
                        write(writer, page.subList(0, Math.min(page.size(), maxRows - offset)));
                        if (page.size() < PAGE_SIZE) {
                            break;
                        }
                    }
                });
            } catch (UncheckedIOException failed) {
                throw failed.getCause();
            }
            writer.flush();
        }

        private void write(Writer writer, List<AuditRecord> page) {
            Map<Long, String> numbers = cases
                    .refs(
                            actor,
                            page.stream()
                                    .map(AuditRecord::caseId)
                                    .filter(Objects::nonNull)
                                    .toList())
                    .entrySet()
                    .stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().number().toString()));
            try {
                for (AuditRecord r : page) {
                    writer.write(CsvFormat.line(List.of(
                            r.at().toString(),
                            Objects.toString(r.actorName(), ""),
                            r.origin().name(),
                            r.entityType(),
                            Objects.toString(r.entityId(), ""),
                            r.action(),
                            r.caseId() == null ? "" : numbers.getOrDefault(r.caseId(), ""),
                            changes(r.changes()),
                            Objects.toString(r.requestId(), ""))));
                }
            } catch (IOException failed) {
                throw new UncheckedIOException(failed);
            }
        }
    }

    /** The changed fields as the screen shows them: "name: before → after", a redacted value as its length. */
    static String changes(AuditChanges changes) {
        return changes.fields().stream()
                .map(field -> field.before() == null
                        ? field.name() + ": " + text(field.after())
                        : field.name() + ": " + text(field.before()) + " → " + text(field.after()))
                .collect(Collectors.joining("; "));
    }

    private static String text(AuditChanges.@Nullable Value value) {
        if (value == null) {
            return "(empty)";
        }
        Integer length = value.redactedLength();
        if (length == null) {
            return Objects.toString(value.text(), "");
        }
        return "redacted (" + length + (length == 1 ? " character)" : " characters)");
    }
}
