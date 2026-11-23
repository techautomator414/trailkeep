// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import io.trailkeep.audit.AuditTrailService.AuditFilter;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.report.AuditExportService.Prepared;
import io.trailkeep.report.AuditExportService.Ready;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.UserPermissions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issue 300, audit-trail CSV, once per vendor: events committed between two page reads neither repeat nor skip a row,
 * and the file holds only the events committed when the export started. The page size is 5, so 23 events (in runs of
 * three at one instant, which only the id tells apart) are five pages. After the first page has been read (the hook is
 * the page's case-number lookup, made once per page inside its transaction) the test commits, on its own connection,
 * events that sort before the cursor (newer than everything read) and events that sort after it (older), all with ids
 * above the highest id at the start. Offset paging repeated a row for the first kind and added the second kind; the
 * file must be exactly the 23 events of the start. The rows are committed, so they are removed afterwards as the
 * schema owner (the application user has no DELETE grant on {@code AUDIT_EVENT}; issue 309).
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {"app.export.page-size=5", "app.export.max-rows=1000"})
abstract class AbstractAuditExportKeysetIT {

    /** Far above any sequence-issued id, so a later event of the application cannot collide with a seeded one. */
    private static final long SEEDED_IDS = 7_000_000_000L;

    private static final long LATE_IDS = 8_000_000_000L;
    private static final int EVENTS = 23;

    @Autowired
    private AuditExportService exports;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @MockitoSpyBean
    private CaseService caseService;

    private long admin;
    private String type;
    private Instant start;

    @BeforeEach
    void seed() {
        admin = new CaseSeed(jdbc).user();
        type = "KEYSET_IT_" + admin;
        start = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        for (int i = 0; i < EVENTS; i++) {
            event(SEEDED_IDS + i, start.minus(2, ChronoUnit.HOURS).plusSeconds(i / 3), i);
        }
    }

    @AfterEach
    void remove() {
        jdbc.update("DELETE FROM AUDIT_EVENT WHERE ENTITY_TYPE = ? OR ACTOR_ID = ?", type, admin);
        jdbc.update("DELETE FROM APP_USER WHERE ID = ?", admin);
    }

    @Test
    void eventsCommittedBetweenPagesNeitherRepeatNorSkipARowAndAreNotInTheFile() throws IOException {
        List<Long> startOfExport = newestFirst();
        assertThat(startOfExport).hasSize(EVENTS);
        AtomicInteger pages = new AtomicInteger();
        doAnswer(call -> {
                    Object refs = call.callRealMethod();
                    if (pages.incrementAndGet() == 1) {
                        committedMeanwhile(() -> {
                            for (int i = 0; i < 4; i++) {
                                event(
                                        LATE_IDS + i,
                                        start.minus(1, ChronoUnit.HOURS).plusSeconds(i),
                                        100 + i);
                                event(
                                        LATE_IDS + 10 + i,
                                        start.minus(3, ChronoUnit.HOURS).plusSeconds(i),
                                        110 + i);
                            }
                        });
                    }
                    return refs;
                })
                .when(caseService)
                .refs(any(), any());

        List<Long> exported = entityIds();

        assertThat(exported)
                .as("the events of the start, newest first, each once; none of the events committed meanwhile")
                .containsExactlyElementsOf(startOfExport);
        assertThat(pages.get()).as("pages read").isEqualTo(5);
    }

    private void event(long id, Instant at, long entityId) {
        jdbc.update(
                "INSERT INTO AUDIT_EVENT (ID, AT_TS, ORIGIN, ENTITY_TYPE, ENTITY_ID, ACTION)"
                        + " VALUES (?, ?, 'SYSTEM', ?, ?, 'KEYSET_IT')",
                id,
                Timestamp.from(at),
                type,
                entityId);
    }

    /** The seeded events as the trail orders them: time then id, both descending. */
    private List<Long> newestFirst() {
        return jdbc.queryForList(
                "SELECT ENTITY_ID FROM AUDIT_EVENT WHERE ENTITY_TYPE = ? ORDER BY AT_TS DESC, ID DESC",
                Long.class,
                type);
    }

    /** The entity ids of the audit export of the test's events, in file order. */
    private List<Long> entityIds() throws IOException {
        Actor actor = new Actor(
                new UserPermissions(admin, false, Set.of(GlobalRole.COMPLIANCE_ADMIN), Map.of()), "Synthetic Exporter");
        AuditFilter filter = new AuditFilter(
                start.minus(6, ChronoUnit.HOURS), start.plus(1, ChronoUnit.HOURS), null, type, null, null);
        Prepared prepared = exports.export(actor, CaseScope.all(), filter, "type=" + type);
        assertThat(prepared).isInstanceOf(Ready.class);
        assertThat(((Ready) prepared).rows()).isEqualTo(EVENTS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ((Ready) prepared).writeTo(out);
        List<String> lines = out.toString(StandardCharsets.UTF_8)
                .substring(1)
                .lines()
                .skip(1)
                .toList();
        List<Long> ids = new ArrayList<>();
        for (String line : lines) {
            String[] cells = line.substring(1, line.length() - 1).split("\",\"", -1);
            ids.add(Long.parseLong(cells[4]));
        }
        return ids;
    }

    /** Commits what the hook writes on a connection of its own: the page being read holds the thread's connection. */
    private void committedMeanwhile(Runnable writes) {
        TransactionTemplate own = new TransactionTemplate(transactions);
        own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        own.executeWithoutResult(status -> writes.run());
    }
}
