// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static io.trailkeep.casefile.FilterBuilder.filter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import io.trailkeep.casefile.CaseExportService.Prepared;
import io.trailkeep.casefile.CaseExportService.Ready;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.UserDirectoryService;
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issue 300, cases CSV, once per vendor: a case committed, moved in or deleted between two page reads cannot make the
 * export repeat a row or skip one, in any of the five sort columns, ascending and descending. The page size is 5, so
 * 23 cases are five pages; after the first page has been read (the hook is the page's owner-name lookup, which both
 * the offset and the keyset code make once per page, inside the page's transaction) the test commits, on its own
 * connection, two cases that sort before the cursor in one direction or the other (the smallest and the largest key
 * of the column, the lowest ids) by moving them into the exported team, and deletes the first row already written. The
 * data is committed, so it is removed afterwards as the schema owner (issue 309).
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {"app.export.page-size=5", "app.export.max-rows=1000"})
abstract class AbstractCaseExportKeysetIT {

    private static final Instant BASE = Instant.parse("2026-03-01T08:00:00Z");

    @Autowired
    private CaseExportService exports;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @MockitoSpyBean
    private UserDirectoryService directory;

    private CaseSeed seed;
    private long team;
    private long parkingTeam;
    private long owner;
    private long admin;
    private final List<Long> cases = new ArrayList<>();

    @BeforeEach
    void seed() {
        seed = new CaseSeed(jdbc);
        team = seed.team();
        parkingTeam = seed.team();
        owner = seed.user();
        admin = seed.user();
    }

    @AfterEach
    void remove() {
        long type = seed.caseType();
        for (long id : cases) {
            jdbc.update("DELETE FROM CASE_SLA WHERE CASE_ID = ?", id);
            jdbc.update("DELETE FROM CASE_RECORD WHERE ID = ?", id);
        }
        jdbc.update("DELETE FROM AUDIT_EVENT WHERE ACTOR_ID = ?", admin);
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, type);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", type);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
        jdbc.update("DELETE FROM TEAM WHERE ID IN (?, ?)", team, parkingTeam);
        jdbc.update("DELETE FROM APP_USER WHERE ID IN (?, ?)", owner, admin);
    }

    @ParameterizedTest(name = "{0} descending={1}")
    @CsvSource({
        "NUMBER,false",
        "NUMBER,true",
        "TITLE,false",
        "TITLE,true",
        "PRIORITY,false",
        "PRIORITY,true",
        "DUE,false",
        "DUE,true",
        "OPENED,false",
        "OPENED,true"
    })
    void casesCommittedOrDeletedBetweenPagesNeitherRepeatNorSkipARow(CaseSort sort, boolean descending)
            throws IOException {
        // The extremes, parked in another team: lowest ids, so they sort first among equals, and out of the export.
        long smallest =
                extreme("SEED-2026-000001", "Synthetic AAA first", "HIGH", BASE.minus(30, ChronoUnit.DAYS), -30);
        long largest = extreme("SEED-2026-899999", "Synthetic ZZZ last", "NORMAL", BASE.plus(30, ChronoUnit.DAYS), 30);
        // Ties in every column (4 titles, 2 priorities, 5 instants, 4 due dates and no due date for a third).
        for (int i = 0; i < 23; i++) {
            cases.add(caseRow(i, team));
        }
        CaseListQuery query = new CaseListQuery(filter().team(team).build(), sort, descending, 1);

        List<String> baseline = numbers(query);
        assertThat(baseline).hasSize(23).doesNotHaveDuplicates();

        long firstWritten = idOf(baseline.get(0));
        AtomicInteger pages = new AtomicInteger();
        doAnswer(call -> {
                    Object names = call.callRealMethod();
                    if (pages.incrementAndGet() == 1) {
                        committedMeanwhile(() -> {
                            jdbc.update(
                                    "UPDATE CASE_RECORD SET TEAM_ID = ? WHERE ID IN (?, ?)", team, smallest, largest);
                            jdbc.update("DELETE FROM CASE_SLA WHERE CASE_ID = ?", firstWritten);
                            jdbc.update("DELETE FROM CASE_RECORD WHERE ID = ?", firstWritten);
                        });
                    }
                    return names;
                })
                .when(directory)
                .displayNames(any());

        List<String> exported = numbers(query);

        assertThat(pages.get()).as("pages read").isGreaterThanOrEqualTo(5);
        assertThat(exported).as("no row twice").doesNotHaveDuplicates();
        List<String> inserted = List.of(number(smallest), number(largest));
        assertThat(exported.stream().filter(n -> !inserted.contains(n)).toList())
                .as("every case that existed, in order, none skipped (the deleted one was already written)")
                .containsExactlyElementsOf(baseline);
    }

    private long caseRow(int i, long inTeam) {
        long id = idOf(seed.caseRow(inTeam, owner, Sensitivity.STANDARD, CaseStatus.OPEN)
                .toString());
        Instant opened = BASE.plus(i % 5, ChronoUnit.HOURS);
        jdbc.update(
                "UPDATE CASE_RECORD SET TITLE = ?, PRIORITY = ?, OPENED_AT = ? WHERE ID = ?",
                "Synthetic tie " + i % 4,
                i % 3 == 0 ? "HIGH" : "NORMAL",
                Timestamp.from(opened),
                id);
        if (i % 3 != 2) {
            due(id, BASE.plus(i % 4, ChronoUnit.DAYS));
        }
        return id;
    }

    private long extreme(String number, String title, String priority, Instant opened, int dueDays) {
        long id = idOf(seed.caseRow(parkingTeam, owner, Sensitivity.STANDARD, CaseStatus.OPEN)
                .toString());
        cases.add(id);
        jdbc.update(
                "UPDATE CASE_RECORD SET CASE_NUMBER = ?, TITLE = ?, PRIORITY = ?, OPENED_AT = ? WHERE ID = ?",
                number,
                title,
                priority,
                Timestamp.from(opened),
                id);
        due(id, BASE.plus(dueDays, ChronoUnit.DAYS));
        return id;
    }

    private void due(long id, Instant dueAt) {
        jdbc.update(
                "INSERT INTO CASE_SLA (CASE_ID, SLA_STATE, SLA_DUE_AT, AT_RISK_AT) VALUES (?, 'ON_TRACK', ?, ?)",
                id,
                Timestamp.from(dueAt),
                Timestamp.from(dueAt.minus(1, ChronoUnit.DAYS)));
    }

    private long idOf(String number) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM CASE_RECORD WHERE CASE_NUMBER = ?", Long.class, number));
    }

    private String number(long id) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT CASE_NUMBER FROM CASE_RECORD WHERE ID = ?", String.class, id));
    }

    /** The case numbers of an export by a Compliance Admin, in file order. */
    private List<String> numbers(CaseListQuery query) throws IOException {
        Actor actor = new Actor(
                new UserPermissions(admin, false, Set.of(GlobalRole.COMPLIANCE_ADMIN), Map.of()), "Synthetic Exporter");
        Prepared prepared = exports.export(actor, query, false);
        assertThat(prepared).isInstanceOf(Ready.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ((Ready) prepared).writeTo(out);
        List<String> lines = out.toString(StandardCharsets.UTF_8)
                .substring(1)
                .lines()
                .skip(1)
                .toList();
        List<String> numbers = new ArrayList<>();
        for (String line : lines) {
            numbers.add(line.substring(1, line.indexOf('"', 1)));
        }
        return numbers;
    }

    /** Commits what the hook writes on a connection of its own: the page being read holds the thread's connection. */
    private void committedMeanwhile(Runnable writes) {
        TransactionTemplate own = new TransactionTemplate(transactions);
        own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        own.executeWithoutResult(status -> writes.run());
    }
}
