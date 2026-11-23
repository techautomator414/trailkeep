// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.casefile.CaseRecordQueries.ExportCursor;
import io.trailkeep.casefile.CaseRecordQueries.ExportPage;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.shared.csv.CsvFormat;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamAdminService.TeamSummary;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserDirectoryService;
import io.trailkeep.user.UserPermissions;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Counts, audits and streams a CSV export. The count and the audit row are one transaction; the rows are read later,
 * when the response body is written, in keyset pages of {@link #PAGE_SIZE}, never more than
 * {@code app.export.max-rows} of them. Each page, with its teams' and owners' names, is read in a short read-only
 * transaction of its own
 * and written to the client after that transaction has ended (Part B "Transactions": no network I/O inside a
 * transaction). One transaction across the whole stream held a connection for as long as the client took to read,
 * and committing the response inside it saves the person's session through a second connection, so as many
 * concurrent exports as the pool has connections deadlocked it until the acquire timeout (WP-30,
 * docs/perf/release-500k.md). Reading the pages in one transaction gave no more consistency: both vendors run at
 * READ COMMITTED, where each statement sees the data committed when it starts, so a page always reflected its own
 * moment.
 *
 * <p>The pages are keyset pages, so that moment cannot shift a boundary: each resumes strictly after the last row of
 * the page before, in the list's order, the person's sort column in the person's direction (any of the five columns,
 * ascending or descending, cases without a due instant last) and then the case id ascending, which is unique. A case
 * committed between two pages is therefore written exactly once if it sorts after the cursor and not at all if it
 * sorts before it (the file is "the cases as each page found them", not a snapshot, since a new case does not
 * belong in an export of the cases that existed at the click); no case is written twice, and none that existed
 * throughout is skipped. The count that the {@code app.export.max-rows} refusal and the audit row use is taken before
 * the first page, and {@code max-rows} still caps what is written.
 */
@Service
@CaseAccessPath
class DefaultCaseExportService implements CaseExportService {

    /** Rows read per statement while streaming. */
    static final int PAGE_SIZE = 500;

    private static final Logger LOG = LoggerFactory.getLogger(DefaultCaseExportService.class);

    private static final List<String> HEADER = List.of(
            "Case number",
            "Title",
            "Status",
            "Sensitivity",
            "Priority",
            "Case type",
            "Team",
            "Owner",
            "SLA state",
            "SLA due on",
            "Opened on",
            "Subject / involved parties",
            "Description");

    private final CaseRecordRepository cases;
    private final CaseScopeService scopes;
    private final UserDirectoryService directory;
    private final AuditRecorder audit;
    private final InstantSource clock;
    private final ZoneId zone;
    private final int maxRows;
    private final int pageSize;
    private final TransactionTemplate readOnly;

    DefaultCaseExportService(
            CaseRecordRepository cases,
            CaseScopeService scopes,
            UserDirectoryService directory,
            AuditRecorder audit,
            InstantSource clock,
            AppProperties app,
            ExportProperties properties,
            PlatformTransactionManager transactionManager) {
        this.cases = cases;
        this.scopes = scopes;
        this.directory = directory;
        this.audit = audit;
        this.clock = clock;
        this.zone = app.timezone();
        this.maxRows = properties.maxRows();
        this.pageSize = properties.pageSize();
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /**
     * What the audit trail keeps of an export.
     *
     * @param rows how many cases matched
     * @param query the list's query string, as the person sent it
     * @param details whether details were included where permitted
     */
    record Exported(long rows, String query, boolean details) {}

    @Override
    @Transactional
    public Prepared export(Actor actor, CaseListQuery requested, boolean details) {
        UserPermissions user = actor.permissions();
        ArchivedCases.check(user, requested.filter());
        CaseScope scope = scopes.forUser(user);
        CaseListQuery query = new CaseListQuery(
                requested.filter().forOwner(user.userId()), requested.sort(), requested.descending(), 1);
        long rows = cases.count(scope, query.filter());
        if (rows > maxRows) {
            return new TooMany(rows, maxRows);
        }
        boolean withDetails = details && offersDetails(user);
        audit.record(new AuditEntry(
                user.userId(),
                actor.displayName(),
                AuditOrigin.UI,
                null,
                "CASE_LIST",
                null,
                "CASES_EXPORTED",
                audit.diff(null, new Exported(rows, requested.onPage(1).toQueryString(), withDetails))));
        LOG.info("Cases list exported: {} rows, details {}", rows, withDetails);
        return new StreamedExport(rows, withDetails, scope, query, user);
    }

    /** The export, read and written when the response body is. */
    private final class StreamedExport implements Ready {

        private final long rows;
        private final boolean details;
        private final CaseScope scope;
        private final CaseListQuery query;
        private final UserPermissions user;

        StreamedExport(long rows, boolean details, CaseScope scope, CaseListQuery query, UserPermissions user) {
            this.rows = rows;
            this.details = details;
            this.scope = scope;
            this.query = query;
            this.user = user;
        }

        @Override
        public long rows() {
            return rows;
        }

        @Override
        public boolean details() {
            return details;
        }

        @Override
        public void writeTo(OutputStream out) throws IOException {
            write(out, scope, query, user, details);
        }
    }

    @Override
    public boolean offersDetails(UserPermissions user) {
        return user.has(GlobalRole.COMPLIANCE_ADMIN) || user.teamRoles().containsValue(TeamRole.MANAGER);
    }

    private void write(OutputStream out, CaseScope scope, CaseListQuery query, UserPermissions user, boolean details)
            throws IOException {
        Writer writer = new BufferedWriter(new OutputStreamWriter(out, UTF_8));
        writer.write(CsvFormat.BOM);
        writer.write(CsvFormat.line(HEADER));
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        int written = 0;
        ExportCursor after = null;
        while (written < maxRows) {
            Page rows = read(scope, query, after);
            written += writePage(writer, rows, today, user, details, maxRows - written);
            after = rows.last();
            if (rows.rows().size() < pageSize) {
                break;
            }
        }
        writer.flush();
    }

    /** One page and the names of its teams and owners (one lookup each), in a read-only transaction of its own. */
    private Page read(CaseScope scope, CaseListQuery query, @Nullable ExportCursor after) {
        return Objects.requireNonNull(readOnly.execute(status -> {
            ExportPage page = cases.export(scope, query, after, pageSize);
            List<CaseExportRow> rows = page.rows();
            Map<Long, String> teams = directory.teams(ids(rows, CaseExportRow::teamId)).values().stream()
                    .collect(Collectors.toUnmodifiableMap(TeamSummary::id, TeamSummary::name));
            return new Page(rows, teams, directory.displayNames(ids(rows, CaseExportRow::ownerId)), page.last());
        }));
    }

    /** Writes one page, after its transaction has ended; returns how many rows it wrote. */
    private int writePage(Writer writer, Page page, LocalDate today, UserPermissions user, boolean details, int room)
            throws IOException {
        int written = 0;
        for (CaseExportRow row : page.rows().subList(0, Math.min(page.rows().size(), room))) {
            boolean shown =
                    details && (user.has(GlobalRole.COMPLIANCE_ADMIN) || user.roleIn(row.teamId()) == TeamRole.MANAGER);
            CaseView.Sla sla = CaseView.Sla.of(row.slaState(), row.slaDueAt(), today, zone);
            writer.write(CsvFormat.line(List.of(
                    row.caseNumber(),
                    row.title(),
                    row.status().name(),
                    row.sensitivity().name(),
                    row.priority().name(),
                    row.caseTypeName(),
                    page.teams().getOrDefault(row.teamId(), ""),
                    page.owners().getOrDefault(row.ownerId(), ""),
                    sla.state().name(),
                    Objects.toString(sla.dueOn(), ""),
                    LocalDate.ofInstant(row.openedAt(), zone).toString(),
                    shown ? text(row.subjectText()) : REDACTED,
                    shown ? text(row.description()) : REDACTED)));
            written++;
        }
        return written;
    }

    /**
     * One page of the export as read.
     *
     * @param rows the rows
     * @param teams team names by id
     * @param owners owner display names by id
     * @param last the cursor the next page resumes from
     */
    private record Page(
            List<CaseExportRow> rows,
            Map<Long, String> teams,
            Map<Long, String> owners,
            @Nullable ExportCursor last) {}

    private static String text(@Nullable String value) {
        return value == null ? "" : value;
    }

    private static Set<Long> ids(List<CaseExportRow> rows, ToLongFunction<CaseExportRow> id) {
        return rows.stream().map(row -> id.applyAsLong(row)).collect(Collectors.toUnmodifiableSet());
    }
}
