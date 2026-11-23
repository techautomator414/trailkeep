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
import io.trailkeep.audit.AuditTrailService.AuditSlice;
import io.trailkeep.audit.AuditTrailService.Position;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.shared.csv.CsvFormat;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
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
 * transaction, and the rows are read when the response body is written, {@value #PAGE_SIZE} a statement, each page
 * with its case numbers in a read-only transaction of its own, and written to the client after that transaction has
 * ended (Part B "Transactions": no network I/O inside a transaction). Holding one transaction across the whole stream
 * held a connection while the response was committed, and committing it saves the person's session through a second
 * connection, so as many concurrent exports as the pool has connections deadlocked it until the acquire timeout
 * (WP-30, docs/perf/release-500k.md).
 *
 * <p><b>The file is a snapshot of the trail up to the moment the export started.</b> Three things make it so. (1) The
 * pages are keyset pages, {@code (AT_TS, ID)} strictly before the last row written, newest first, so an event
 * committed between two pages cannot shift a boundary: nothing is written twice and nothing is skipped (offset pages
 * did both). (2) The filter's end is pinned to the start (milliseconds), so an event stamped later is out. (3) Only
 * events with an id up to the highest id committed at the start are read, so an event committed after the start is
 * out even if it carries an earlier time (a slow request that stamped the event before this export began but
 * committed after). The count and the {@code app.export.max-rows} refusal use the same bound. This export's own audit
 * event is written after the bound is read, so it is not in its file.
 *
 * <p>The limit: ids come from a pooled sequence (blocks of 50 per application instance) and read-committed isolation
 * is kept (one long snapshot is what deadlocked the pool), so an event that was still uncommitted at the start, or
 * written by another instance with an id below the bound, can be included if it commits before the export reaches its
 * position, and is then in the file exactly once.
 */
@Service
class DefaultAuditExportService implements AuditExportService {

    /** Rows read per statement while streaming, unless {@code app.export.page-size} says fewer. */
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
    private final int pageSize;
    private final TransactionTemplate readOnly;

    DefaultAuditExportService(
            AuditTrailService trail,
            AuditRecorder audit,
            CaseService cases,
            InstantSource clock,
            @Value("${app.export.max-rows:10000}") int maxRows,
            @Value("${app.export.page-size:" + PAGE_SIZE + "}") int pageSize,
            PlatformTransactionManager transactionManager) {
        this.trail = trail;
        this.audit = audit;
        this.cases = cases;
        this.clock = clock;
        this.maxRows = maxRows;
        this.pageSize = Math.clamp(pageSize, 1, PAGE_SIZE);
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
        // Read before the count and before this export's own audit event is written, so neither is in the bound.
        long newestId = trail.newestId(scope);
        long rows = trail.count(scope, filter, newestId);
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
        return new Streamed(rows, actor, scope, filter, newestId);
    }

    /** The export, read and written when the response body is. */
    private final class Streamed implements Ready {

        private final long rows;
        private final Actor actor;
        private final CaseScope scope;
        private final AuditFilter filter;
        private final long newestId;

        Streamed(long rows, Actor actor, CaseScope scope, AuditFilter filter, long newestId) {
            this.newestId = newestId;
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
            Position after = null;
            for (int written = 0; written < maxRows; ) {
                Page page = read(after, maxRows - written);
                write(writer, page);
                written += page.records().size();
                after = page.last();
                if (page.fetched() < pageSize) {
                    break;
                }
            }
            writer.flush();
        }

        /**
         * The page after a position, up to {@code room} of its events, and their case numbers, in a transaction of its
         * own.
         */
        private Page read(@Nullable Position after, int room) {
            return Objects.requireNonNull(readOnly.execute(status -> {
                AuditSlice slice = trail.searchAfter(scope, filter, newestId, after, pageSize);
                List<AuditRecord> fetched = slice.records();
                List<AuditRecord> records = fetched.subList(0, Math.min(fetched.size(), room));
                Map<Long, String> numbers = cases
                        .refs(
                                actor,
                                records.stream()
                                        .map(AuditRecord::caseId)
                                        .filter(Objects::nonNull)
                                        .toList())
                        .entrySet()
                        .stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().number().toString()));
                return new Page(List.copyOf(records), fetched.size(), numbers, slice.last());
            }));
        }

        /** Writes one page, after its transaction has ended. */
        private void write(Writer writer, Page page) throws IOException {
            for (AuditRecord r : page.records()) {
                writer.write(CsvFormat.line(List.of(
                        r.at().toString(),
                        Objects.toString(r.actorName(), ""),
                        r.origin().name(),
                        r.entityType(),
                        Objects.toString(r.entityId(), ""),
                        r.action(),
                        r.caseId() == null ? "" : page.numbers().getOrDefault(r.caseId(), ""),
                        changes(r.changes()),
                        Objects.toString(r.requestId(), ""))));
            }
        }
    }

    /**
     * One page of an audit export as read.
     *
     * @param records the events to write
     * @param fetched how many events the page's statement returned (fewer than a page: the last)
     * @param numbers case numbers by case id
     * @param last the position of the last event fetched, where the next page resumes
     */
    private record Page(
            List<AuditRecord> records,
            int fetched,
            Map<Long, String> numbers,
            @Nullable Position last) {}

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
