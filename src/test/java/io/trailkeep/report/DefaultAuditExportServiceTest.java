// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.trailkeep.audit.AuditChanges;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecord;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.audit.AuditTrailService;
import io.trailkeep.audit.AuditTrailService.AuditFilter;
import io.trailkeep.audit.AuditTrailService.AuditPage;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.CaseService.CaseRef;
import io.trailkeep.report.AuditExportService.Ready;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.UserPermissions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
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
 * The audit export never writes to the client inside a transaction (WP-30): each page of events and its case numbers
 * is read in a read-only transaction of its own, and every byte reaches the response stream after that transaction
 * has ended. Committing the response saves the person's session through another connection, so a write inside the
 * read transaction needed two connections at once and enough concurrent exports deadlocked the pool.
 */
class DefaultAuditExportServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
    private static final int PAGE = DefaultAuditExportService.PAGE_SIZE;
    private static final long CASE_ID = 42L;

    private final AtomicInteger open = new AtomicInteger();
    private final AtomicInteger begun = new AtomicInteger();
    private final List<Integer> openWhileReading = new CopyOnWriteArrayList<>();

    @Test
    void eachPageIsReadInItsOwnTransactionAndWrittenAfterItEnds() throws IOException {
        GuardedStream out = export(PAGE * 2 + 1, 10_000);

        assertThat(out.writesInsideTransaction).isZero();
        assertThat(out.writes).isPositive();
        assertThat(begun).as("one transaction per page").hasValue(3);
        assertThat(openWhileReading)
                .as("events and case numbers read inside a transaction")
                .hasSize(6)
                .containsOnly(1);
        String csv = out.text();
        assertThat(csv.split("\r\n", -1)).hasSize(PAGE * 2 + 1 + 2);
        assertThat(csv).contains("\"CASE-2026-000042\"", "\"Synthetic Actor " + (PAGE * 2) + "\"");
    }

    @Test
    void theCapEndsTheExportPartWayThroughPage() throws IOException {
        GuardedStream out = export(PAGE * 2, PAGE + 3);

        assertThat(out.writesInsideTransaction).isZero();
        assertThat(begun).hasValue(2);
        assertThat(out.text().split("\r\n", -1)).hasSize(PAGE + 3 + 2);
    }

    /** Exports {@code events} synthetic events, at most {@code maxRows}, into a guarded stream. */
    private GuardedStream export(int events, int maxRows) throws IOException {
        AuditTrailService trail = mock(AuditTrailService.class);
        when(trail.search(any(), any(), anyInt(), anyInt())).thenAnswer(call -> {
            int offset = call.getArgument(2);
            int limit = call.getArgument(3);
            if (limit > 1) {
                openWhileReading.add(open.get());
            }
            List<AuditRecord> page = IntStream.range(offset, Math.min(events, offset + limit))
                    .mapToObj(DefaultAuditExportServiceTest::event)
                    .toList();
            // The count sees at most the cap, as if the rest arrived after it: the stream must still stop there.
            return new AuditPage(page, limit == 1 ? Math.min(events, maxRows) : events);
        });
        CaseService cases = mock(CaseService.class);
        when(cases.refs(any(), any())).thenAnswer(call -> {
            openWhileReading.add(open.get());
            return Map.of(CASE_ID, new CaseRef(CASE_ID, CaseNumber.parse("CASE-2026-000042"), "Synthetic", 1L));
        });
        AuditRecorder audit = mock(AuditRecorder.class);
        when(audit.diff(any(), any())).thenReturn(AuditChanges.none());
        DefaultAuditExportService exports = new DefaultAuditExportService(
                trail, audit, cases, InstantSource.fixed(NOW), maxRows, new CountingTransactionManager());
        Actor admin = new Actor(new UserPermissions(1L, false, Set.of(GlobalRole.COMPLIANCE_ADMIN), Map.of()), "Admin");
        AuditFilter lastWeek =
                new AuditFilter(NOW.minusSeconds(7 * 86_400L), NOW.minusSeconds(60), null, null, null, null);

        AuditExportService.Prepared prepared = exports.export(admin, mock(CaseScope.class), lastWeek, "from=x");
        assertThat(prepared).isInstanceOf(Ready.class);
        GuardedStream out = new GuardedStream();
        ((Ready) prepared).writeTo(out);
        return out;
    }

    private static AuditRecord event(int i) {
        return new AuditRecord(
                NOW.minusSeconds(i + 120L),
                (long) i,
                "Synthetic Actor " + i,
                AuditOrigin.UI,
                i % 2 == 0 ? CASE_ID : null,
                "CASE",
                (long) i,
                "CASE_UPDATED",
                AuditChanges.none(),
                null);
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
