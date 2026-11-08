// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.retention;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.shared.persistence.DatabaseProperties;
import io.trailkeep.shared.persistence.DatabaseVendor;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.TestDatabaseUsers;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The purge, once per vendor (WP-24 part c), run as the {@code TRAILKEEP_PURGE} user itself over its own connection,
 * so every statement is proven against the purge user's grants and nothing more. On committed rows: an archived case
 * past its {@code PURGE_AFTER} with a comment and its pin, an update request answered by that comment, an attachment
 * and its chunk, search text, a closure request and the reopen that names it, and mail about it, becomes a tombstone
 * (description and subject cleared, {@code PURGED_AT} set, title and number kept, a new version) with all of that
 * deleted, while its audit row, ledger row, legal hold event and in-app alert stay. A held case, a case not yet due and
 * a closed case are untouched; a hold placed between the scan and the purge wins; a second run purges nothing. Report
 * runs older than the retention go, and the mail naming them first; a recent run stays.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractPurgeIT {

    private static final AtomicLong NEXT = new AtomicLong(986_000);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestDatabaseUsers users;

    @Value("${app.db.purge-user}")
    private String purgeUser = "";

    @Autowired
    private DatabaseProperties database;

    private final List<Long> cases = new ArrayList<>();
    private Connection connection;
    private PurgeJdbcDao purge;
    private Instant now = Instant.EPOCH;
    private long user;
    private long approver;
    private long team;
    private long caseType;
    private long schedule;

    @BeforeEach
    void connectAsThePurgeUser() throws SQLException {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        connection = users.login(purgeUser);
        if (database.vendor() == DatabaseVendor.ORACLE) {
            try (Statement statement = connection.createStatement()) {
                // Test-only: the owner schema is the container user's name, as TestDatabaseUsers reports it.
                statement.execute("ALTER SESSION SET CURRENT_SCHEMA = " + users.ownerSchema());
            }
        }
        SingleConnectionDataSource source = new SingleConnectionDataSource(connection, true);
        purge = new PurgeJdbcDao(
                new NamedParameterJdbcTemplate(source),
                new TransactionTemplate(new DataSourceTransactionManager(source)));
        CaseSeed seed = new CaseSeed(jdbc);
        user = seed.user();
        approver = seed.user();
        team = seed.team();
        caseType = seed.caseType();
    }

    @AfterEach
    void removeCommittedRows() throws SQLException {
        connection.close();
        // Test-only cleanup of the committed fixture, as the schema owner.
        for (String table : List.of(
                "CASE_COMMENT_PIN",
                "UPDATE_REQUEST",
                "CASE_COMMENT",
                "CASE_SEARCH",
                "CASE_REOPEN",
                "CLOSURE_REQUEST",
                "NOTIFICATION_OUTBOX",
                "USER_ALERT",
                "AUDIT_EVENT",
                "ALERT_LEDGER",
                "LEGAL_HOLD_EVENT",
                "CASE_SLA")) {
            for (long id : cases) {
                jdbc.update("DELETE FROM " + table + " WHERE CASE_ID = ?", id);
            }
        }
        for (long id : cases) {
            jdbc.update(
                    "DELETE FROM CASE_ATTACHMENT_CHUNK WHERE ATTACHMENT_ID IN"
                            + " (SELECT ID FROM CASE_ATTACHMENT WHERE CASE_ID = ?)",
                    id);
            jdbc.update("DELETE FROM CASE_ATTACHMENT WHERE CASE_ID = ?", id);
            jdbc.update("DELETE FROM CASE_RECORD WHERE ID = ?", id);
        }
        jdbc.update(
                "DELETE FROM NOTIFICATION_OUTBOX WHERE REPORT_RUN_ID IN"
                        + " (SELECT ID FROM REPORT_RUN WHERE TEAM_ID = ?)",
                team);
        jdbc.update("DELETE FROM REPORT_RUN WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM REPORT_SCHEDULE WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
        jdbc.update("DELETE FROM APP_USER WHERE ID = ?", user);
        jdbc.update("DELETE FROM APP_USER WHERE ID = ?", approver);
    }

    @Test
    void anArchivedCasePastItsDateBecomesATombstoneAndKeepsItsHistory() {
        final long due = archived(now.minus(Duration.ofDays(1)), false);
        final long held = archived(now.minus(Duration.ofDays(1)), true);
        final long notYet = archived(now.plus(Duration.ofDays(1)), false);
        final long closed = kase(CaseStatus.CLOSED, null, false);
        for (long id : List.of(due, held, notYet, closed)) {
            content(id);
        }

        Map<String, Integer> counts = new PurgeJob(purge, Duration.ofDays(400)).run(now);

        assertThat(counts)
                .containsEntry("cases", 1)
                .containsEntry("comments", 1)
                .containsEntry("pins", 1);
        assertThat(counts)
                .containsEntry("attachments", 1)
                .containsEntry("chunks", 1)
                .containsEntry("reopens", 1);
        assertThat(counts).containsEntry("closureRequests", 1).containsEntry("updateRequests", 1);
        Map<String, Object> tombstone = jdbc.queryForMap(
                "SELECT TITLE, DESCRIPTION, SUBJECT_TEXT, PURGED_AT, VERSION FROM CASE_RECORD WHERE ID = ?", due);
        assertThat(tombstone.get("TITLE")).isEqualTo("Seed case " + due);
        assertThat(tombstone.get("DESCRIPTION")).isNull();
        assertThat(tombstone.get("SUBJECT_TEXT")).isNull();
        assertThat(((Number) Objects.requireNonNull(tombstone.get("VERSION"))).intValue())
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT PURGED_AT FROM CASE_RECORD WHERE ID = ?", Timestamp.class, due))
                .isEqualTo(Timestamp.from(now));
        assertThat(contentRows(due)).isZero();
        assertThat(historyRows(due)).isEqualTo(4);
        for (long id : List.of(held, notYet, closed)) {
            assertThat(contentRows(id)).as("case %s", id).isEqualTo(9);
            assertThat(jdbc.queryForObject("SELECT PURGED_AT FROM CASE_RECORD WHERE ID = ?", Timestamp.class, id))
                    .isNull();
        }

        assertThat(new PurgeJob(purge, Duration.ofDays(400)).run(now)).containsEntry("reportRuns", 0);
        assertThat(purge.due(now)).doesNotContain(due);
    }

    @Test
    void holdsPlacedBetweenTheScanAndThePurgeWin() {
        long id = archived(now.minus(Duration.ofDays(1)), false);
        content(id);
        assertThat(purge.due(now)).contains(id);
        jdbc.update("UPDATE CASE_RECORD SET LEGAL_HOLD = ? WHERE ID = ?", true, id);

        assertThat(purge.purge(List.of(id), now)).isEmpty();
        assertThat(contentRows(id)).isEqualTo(9);
    }

    @Test
    void oldReportRunsGoWithTheMailNamingThemAndRecentOnesStay() {
        schedule = NEXT.incrementAndGet();
        jdbc.update(
                "INSERT INTO REPORT_SCHEDULE (ID, TEAM_ID, RUN_WEEKDAY, RUN_HOUR, NEXT_RUN_AT) VALUES (?, ?, 1, 6, ?)",
                schedule,
                team,
                Timestamp.from(now));
        long old = run(now.minus(Duration.ofDays(401)));
        long recent = run(now.minus(Duration.ofDays(7)));

        assertThat(purge.trimReportRuns(now.minus(Duration.ofDays(400)))).isEqualTo(1);

        assertThat(jdbc.queryForList("SELECT ID FROM REPORT_RUN WHERE TEAM_ID = ?", Long.class, team))
                .containsExactly(recent);
        assertThat(count("SELECT COUNT(*) FROM NOTIFICATION_OUTBOX WHERE REPORT_RUN_ID = ?", old))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM NOTIFICATION_OUTBOX WHERE REPORT_RUN_ID = ?", recent))
                .isEqualTo(1);
    }

    private long archived(Instant purgeAfter, boolean held) {
        long id = kase(CaseStatus.ARCHIVED, purgeAfter, held);
        return id;
    }

    private long kase(CaseStatus status, @Nullable Instant purgeAfter, boolean held) {
        CaseSeed seed = new CaseSeed(jdbc);
        long id = seed.caseId(seed.caseRow(team, user, Sensitivity.STANDARD, status));
        cases.add(id);
        jdbc.update(
                "UPDATE CASE_RECORD SET CASE_TYPE_ID = ?, SUBJECT_TEXT = 'Synthetic subject', CLOSED_AT = ?,"
                        + " PURGE_AFTER = ?, LEGAL_HOLD = ? WHERE ID = ?",
                caseType,
                Timestamp.from(now.minus(Duration.ofDays(2000))),
                purgeAfter == null ? null : Timestamp.from(purgeAfter),
                held,
                id);
        return id;
    }

    /** Nine rows of case content and four of case history, each in its own table. */
    private void content(long caseId) {
        CaseSeed seed = new CaseSeed(jdbc);
        long comment = seed.comment(caseId, user, "Synthetic comment");
        seed.pin(comment, caseId, user);
        long attachment = seed.attachment(caseId, user, "synthetic.txt", "CLEAN");
        jdbc.update(
                "INSERT INTO CASE_ATTACHMENT_CHUNK (ATTACHMENT_ID, SEQ, CHUNK) VALUES (?, 0, ?)",
                attachment,
                new byte[] {1});
        jdbc.update(
                "INSERT INTO CASE_SEARCH (CASE_ID, SEARCH_TEXT, UPDATED_AT) VALUES (?, 'synthetic', ?)",
                caseId,
                Timestamp.from(now));
        jdbc.update(
                "INSERT INTO UPDATE_REQUEST (ID, CASE_ID, REQUESTED_BY, REQUESTED_FROM, MESSAGE, REQUESTED_AT,"
                        + " ANSWERED_AT, ANSWERED_BY, ANSWERED_BY_COMMENT_ID)"
                        + " VALUES (?, ?, ?, ?, 'Synthetic', ?, ?, ?, ?)",
                NEXT.incrementAndGet(),
                caseId,
                user,
                user,
                Timestamp.from(now),
                Timestamp.from(now),
                user,
                comment);
        long closure = NEXT.incrementAndGet();
        jdbc.update(
                "INSERT INTO CLOSURE_REQUEST (ID, CASE_ID, REQUESTED_BY, APPROVER_ID, REQUESTED_AT, OUTCOME,"
                        + " RESOLUTION_SUMMARY, DECISION, DECIDED_BY, DECIDED_AT) VALUES (?, ?, ?, ?, ?, 'Resolved',"
                        + " 'Synthetic summary', 'APPROVED', ?, ?)",
                closure,
                caseId,
                user,
                approver,
                Timestamp.from(now),
                approver,
                Timestamp.from(now));
        jdbc.update(
                "INSERT INTO CASE_REOPEN (ID, CASE_ID, REOPENED_BY, REOPENED_AT, REASON, SLA_CYCLE, CLOSURE_REQUEST_ID)"
                        + " VALUES (?, ?, ?, ?, 'Synthetic reason', 2, ?)",
                NEXT.incrementAndGet(),
                caseId,
                user,
                Timestamp.from(now),
                closure);
        mail(caseId, null);
        history(caseId);
    }

    /** The history that outlives the purge: an audit row, a ledger row, a legal hold event and an in-app alert. */
    private void history(long caseId) {
        Timestamp at = Timestamp.from(now);
        jdbc.update(
                "INSERT INTO AUDIT_EVENT (ID, AT_TS, ORIGIN, CASE_ID, ENTITY_TYPE, ACTION) VALUES (?, ?, 'UI', ?,"
                        + " 'CASE', 'CASE_CREATED')",
                NEXT.incrementAndGet(),
                at,
                caseId);
        jdbc.update(
                "INSERT INTO ALERT_LEDGER (ID, CASE_ID, ALERT_TYPE, SLA_CYCLE, PERIOD_KEY, RECORDED_AT)"
                        + " VALUES (?, ?, 'SLA_OVERDUE', 1, 'CYCLE', ?)",
                NEXT.incrementAndGet(),
                caseId,
                at);
        jdbc.update(
                "INSERT INTO LEGAL_HOLD_EVENT (ID, CASE_ID, ACTION, CHANGED_BY, CHANGED_AT, REASON)"
                        + " VALUES (?, ?, 'PLACED', ?, ?, 'Synthetic hold')",
                NEXT.incrementAndGet(),
                caseId,
                user,
                at);
        jdbc.update(
                "INSERT INTO USER_ALERT (ID, USER_ID, CASE_ID, MESSAGE, LINK, CREATED_AT)"
                        + " VALUES (?, ?, ?, 'Synthetic alert', '/cases', ?)",
                NEXT.incrementAndGet(),
                user,
                caseId,
                at);
    }

    private void mail(@Nullable Long caseId, @Nullable Long reportRun) {
        jdbc.update(
                "INSERT INTO NOTIFICATION_OUTBOX (ID, RECIPIENT, TEMPLATE_ID, SUBJECT, BODY_TEXT, BODY_HTML,"
                        + " NEXT_ATTEMPT_AT, CREATED_AT, CASE_ID, REPORT_RUN_ID)"
                        + " VALUES (?, 'seed@example.test', 'comment-added', 'Synthetic', 'text', 'html', ?, ?, ?, ?)",
                NEXT.incrementAndGet(),
                Timestamp.from(now),
                Timestamp.from(now),
                caseId,
                reportRun);
    }

    private long run(Instant ranAt) {
        long id = NEXT.incrementAndGet();
        jdbc.update(
                "INSERT INTO REPORT_RUN (ID, SCHEDULE_ID, TEAM_ID, REPORT, RAN_AT, AS_OF, LINE_COUNT, HTML, CSV)"
                        + " VALUES (?, ?, ?, 'AGING', ?, ?, 0, 'html', 'csv')",
                id,
                schedule,
                team,
                Timestamp.from(ranAt),
                java.sql.Date.valueOf(java.time.LocalDate.ofInstant(ranAt, java.time.ZoneOffset.UTC)));
        mail(null, id);
        return id;
    }

    private long contentRows(long caseId) {
        long rows = 0;
        for (String table : List.of(
                "CASE_COMMENT_PIN",
                "UPDATE_REQUEST",
                "CASE_COMMENT",
                "CASE_ATTACHMENT",
                "CASE_SEARCH",
                "CASE_REOPEN",
                "CLOSURE_REQUEST",
                "NOTIFICATION_OUTBOX")) {
            rows += count("SELECT COUNT(*) FROM " + table + " WHERE CASE_ID = ?", caseId);
        }
        return rows
                + count(
                        "SELECT COUNT(*) FROM CASE_ATTACHMENT_CHUNK WHERE ATTACHMENT_ID IN"
                                + " (SELECT ID FROM CASE_ATTACHMENT WHERE CASE_ID = ?)",
                        caseId);
    }

    private long historyRows(long caseId) {
        long rows = 0;
        for (String table : List.of("AUDIT_EVENT", "ALERT_LEDGER", "LEGAL_HOLD_EVENT", "USER_ALERT")) {
            rows += count("SELECT COUNT(*) FROM " + table + " WHERE CASE_ID = ?", caseId);
        }
        return rows;
    }

    private long count(String sql, Object arg) {
        Long count = jdbc.queryForObject(sql, Long.class, arg);
        return count == null ? 0 : count;
    }
}
