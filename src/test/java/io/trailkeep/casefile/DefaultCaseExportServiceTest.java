// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.trailkeep.audit.AuditChanges;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.casefile.CaseExportService.Ready;
import io.trailkeep.casefile.CaseRecordQueries.ExportCursor;
import io.trailkeep.casefile.CaseRecordQueries.ExportPage;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamAdminService.TeamSummary;
import io.trailkeep.user.UserDirectoryService;
import io.trailkeep.user.UserPermissions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The export never writes to the client inside a transaction (WP-30, #84/#91; Part B "Transactions"): each page is
 * read in a read-only transaction of its own, and every byte reaches the response stream after that transaction has
 * ended, so a client that reads slowly holds no database connection. The transaction manager here counts open
 * transactions; the response stream and the reads record the count on every call.
 */
class DefaultCaseExportServiceTest {

    private static final long TEAM = 7L;
    private static final long OWNER = 11L;
    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
    private static final int PAGE = DefaultCaseExportService.PAGE_SIZE;
    private static final CaseListQuery ALL = new CaseListQuery(CaseFilter.none(), CaseSort.NUMBER, false, 1);

    private final AtomicInteger open = new AtomicInteger();
    private final AtomicInteger begun = new AtomicInteger();
    private final List<Integer> openWhileReading = new CopyOnWriteArrayList<>();

    @Test
    void eachPageIsReadInItsOwnTransactionAndWrittenAfterItEnds() throws IOException {
        int rows = PAGE * 2 + 1;
        Actor admin = new Actor(new UserPermissions(1L, false, Set.of(GlobalRole.COMPLIANCE_ADMIN), Map.of()), "Admin");

        GuardedStream out = export(rows, admin, true);

        assertThat(out.writesInsideTransaction).isZero();
        assertThat(out.writes).isGreaterThan(rows / 100);
        assertThat(begun).as("one transaction per page").hasValue(3);
        assertThat(openWhileReading)
                .as("rows and names read inside a transaction")
                .hasSize(9)
                .containsOnly(1);
        String csv = out.text();
        assertThat(csv.split("\r\n", -1)).hasSize(rows + 2);
        assertThat(csv).contains("\"CASE-2026-000001\"", "\"Synthetic Team\"", "\"Synthetic Owner\"");
        assertThat(csv).contains("\"" + number(rows - 1) + "\"", "\"Synthetic description " + (rows - 1) + "\"");
    }

    @Test
    void shortExportIsOneTransactionAndIsWrittenAfterItEnds() throws IOException {
        Actor analyst = new Actor(new UserPermissions(1L, false, Set.of(), Map.of()), "Analyst");

        GuardedStream out = export(3, analyst, false);

        assertThat(out.writesInsideTransaction).isZero();
        assertThat(begun).hasValue(1);
        assertThat(out.text().split("\r\n", -1)).hasSize(5);
        assertThat(out.text()).contains("\"" + CaseExportService.REDACTED + "\"");
    }

    /** Exports {@code rows} synthetic cases into a guarded stream. */
    private GuardedStream export(int rows, Actor actor, boolean details) throws IOException {
        CaseRecordRepository cases = mock(CaseRecordRepository.class);
        when(cases.count(any(), any())).thenReturn((long) rows);
        when(cases.export(any(), any(), any(), anyInt())).thenAnswer(call -> {
            openWhileReading.add(open.get());
            ExportCursor after = call.getArgument(2);
            int first = after == null ? 0 : (int) after.id() + 1;
            List<CaseExportRow> page = IntStream.range(first, Math.min(rows, first + PAGE))
                    .mapToObj(DefaultCaseExportServiceTest::row)
                    .toList();
            return new ExportPage(page, page.isEmpty() ? null : new ExportCursor(null, first + page.size() - 1));
        });
        UserDirectoryService directory = mock(UserDirectoryService.class);
        when(directory.teams(any())).thenAnswer(call -> {
            openWhileReading.add(open.get());
            return Map.of(TEAM, new TeamSummary(TEAM, "Synthetic Team", true));
        });
        when(directory.displayNames(any())).thenAnswer(call -> {
            openWhileReading.add(open.get());
            return Map.of(OWNER, "Synthetic Owner");
        });
        CaseScopeService scopes = mock(CaseScopeService.class);
        when(scopes.forUser(any())).thenReturn(mock(CaseScope.class));
        AuditRecorder audit = mock(AuditRecorder.class);
        when(audit.diff(any(), any())).thenReturn(AuditChanges.none());
        AppProperties app = mock(AppProperties.class);
        when(app.timezone()).thenReturn(ZoneOffset.UTC);
        DefaultCaseExportService exports = new DefaultCaseExportService(
                cases,
                scopes,
                directory,
                audit,
                InstantSource.fixed(NOW),
                app,
                new ExportProperties(10_000, PAGE),
                new CountingTransactionManager());

        Ready ready = (Ready) exports.export(actor, ALL, details);
        GuardedStream out = new GuardedStream();
        ready.writeTo(out);
        return out;
    }

    private static String number(int i) {
        return String.format(Locale.ROOT, "CASE-2026-%06d", i + 1);
    }

    private static CaseExportRow row(int i) {
        return new CaseExportRow(
                number(i),
                "Synthetic title " + i,
                CaseStatus.OPEN,
                Sensitivity.STANDARD,
                CasePriority.NORMAL,
                "Synthetic type",
                TEAM,
                OWNER,
                NOW,
                null,
                null,
                "Synthetic subject " + i,
                "Synthetic description " + i);
    }

    /** Counts the transactions begun and open; a template's begin and commit go through here. */
    private final class CountingTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(@Nullable TransactionDefinition definition) {
            open.incrementAndGet();
            begun.incrementAndGet();
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            open.decrementAndGet();
        }

        @Override
        public void rollback(TransactionStatus status) {
            open.decrementAndGet();
        }
    }

    /** A response stream that records every write and whether a transaction was open at the time. */
    private final class GuardedStream extends OutputStream {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int writes;
        private int writesInsideTransaction;

        @Override
        public void write(int b) {
            check();
            bytes.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            check();
            bytes.write(b, off, len);
        }

        String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }

        private void check() {
            writes++;
            if (open.get() > 0) {
                writesInsideTransaction++;
            }
        }
    }
}
