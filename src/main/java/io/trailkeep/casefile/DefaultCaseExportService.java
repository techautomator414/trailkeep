// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamAdminService.TeamSummary;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserDirectoryService;
import io.trailkeep.user.UserPermissions;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
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
 * when the response body is written, in pages of {@link #PAGE_SIZE} inside one read-only transaction of their own
 * (the attachment download pattern), never more than {@code app.export.max-rows} of them.
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
        try {
            readOnly.executeWithoutResult(status -> {
                int written = 0;
                for (int page = 1; written < maxRows; page++) {
                    List<CaseExportRow> rows = cases.export(scope, query.onPage(page), PAGE_SIZE);
                    written += write(writer, rows, today, user, details, maxRows - written);
                    if (rows.size() < PAGE_SIZE) {
                        break;
                    }
                }
            });
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
        writer.flush();
    }

    /** Writes one page, naming its teams and owners in one lookup each; returns how many rows it wrote. */
    private int write(
            Writer writer, List<CaseExportRow> rows, LocalDate today, UserPermissions user, boolean details, int room) {
        Map<Long, String> teams = directory.teams(ids(rows, CaseExportRow::teamId)).values().stream()
                .collect(Collectors.toUnmodifiableMap(TeamSummary::id, TeamSummary::name));
        Map<Long, String> owners = directory.displayNames(ids(rows, CaseExportRow::ownerId));
        int written = 0;
        try {
            for (CaseExportRow row : rows.subList(0, Math.min(rows.size(), room))) {
                boolean shown = details
                        && (user.has(GlobalRole.COMPLIANCE_ADMIN) || user.roleIn(row.teamId()) == TeamRole.MANAGER);
                CaseView.Sla sla = CaseView.Sla.of(row.slaState(), row.slaDueAt(), today, zone);
                writer.write(CsvFormat.line(List.of(
                        row.caseNumber(),
                        row.title(),
                        row.status().name(),
                        row.sensitivity().name(),
                        row.priority().name(),
                        row.caseTypeName(),
                        teams.getOrDefault(row.teamId(), ""),
                        owners.getOrDefault(row.ownerId(), ""),
                        sla.state().name(),
                        Objects.toString(sla.dueOn(), ""),
                        LocalDate.ofInstant(row.openedAt(), zone).toString(),
                        shown ? text(row.subjectText()) : REDACTED,
                        shown ? text(row.description()) : REDACTED)));
                written++;
            }
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
        return written;
    }

    private static String text(@Nullable String value) {
        return value == null ? "" : value;
    }

    private static Set<Long> ids(List<CaseExportRow> rows, ToLongFunction<CaseExportRow> id) {
        return rows.stream().map(row -> id.applyAsLong(row)).collect(Collectors.toUnmodifiableSet());
    }
}
