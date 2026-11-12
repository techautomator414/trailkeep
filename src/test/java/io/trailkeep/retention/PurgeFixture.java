// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.retention;

import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Committed rows for {@link AbstractPurgeIT}, written and removed as the schema owner: a user, an approver, a team, a
 * case type, cases in any status with ten rows of content and four of history each, report runs and their mail.
 */
final class PurgeFixture {

    private static final AtomicLong NEXT = new AtomicLong(986_000);

    /** The content tables counted by {@link #contentRows}, chunks aside. */
    private static final List<String> CONTENT = List.of(
            "CASE_COMMENT_PIN",
            "UPDATE_REQUEST",
            "CASE_COMMENT",
            "CASE_IMPORTED_EVENT",
            "CASE_ATTACHMENT",
            "CASE_SEARCH",
            "CASE_REOPEN",
            "CLOSURE_REQUEST",
            "NOTIFICATION_OUTBOX");

    private final JdbcTemplate jdbc;
    private final Instant now;
    private final List<Long> cases = new ArrayList<>();
    private final long user;
    private final long approver;
    private final long team;
    private final long caseType;
    private long schedule;
    private long importRun;

    PurgeFixture(JdbcTemplate jdbc, Instant now) {
        this.jdbc = jdbc;
        this.now = now;
        CaseSeed seed = new CaseSeed(jdbc);
        this.user = seed.user();
        this.approver = seed.user();
        this.team = seed.team();
        this.caseType = seed.caseType();
    }

    long team() {
        return team;
    }

    /** The first case written, or a new archived one not yet due. */
    long anyCase() {
        return cases.isEmpty() ? archived(now.plus(Duration.ofDays(1)), false) : cases.getFirst();
    }

    long archived(Instant purgeAfter, boolean held) {
        return kase(CaseStatus.ARCHIVED, purgeAfter, held);
    }

    long kase(CaseStatus status, @Nullable Instant purgeAfter, boolean held) {
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
    void content(long caseId) {
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
        importedEvent(caseId);
        history(caseId);
    }

    /** One row of imported legacy history (V16), from this fixture's one import run. */
    private void importedEvent(long caseId) {
        if (importRun == 0) {
            importRun = NEXT.incrementAndGet();
            jdbc.update(
                    "INSERT INTO IMPORT_RUN (ID, KIND, SOURCE_ID, STATUS, REQUESTED_BY, REQUESTED_AT, UPDATED_AT)"
                            + " VALUES (?, 'IMPORT', 'synthetic', 'LOADED', ?, ?, ?)",
                    importRun,
                    user,
                    Timestamp.from(now),
                    Timestamp.from(now));
        }
        jdbc.update(
                "INSERT INTO CASE_IMPORTED_EVENT (ID, CASE_ID, AT_TS, KIND, FIELD_NAME, OLD_VALUE, NEW_VALUE, RUN_ID)"
                        + " VALUES (?, ?, ?, 'STATUS_CHANGE', 'status', 'New', 'Done', ?)",
                NEXT.incrementAndGet(),
                caseId,
                Timestamp.from(now),
                importRun);
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

    /** A report run of the team's schedule (written with the first run), with one mail naming it. */
    long run(Instant ranAt) {
        if (schedule == 0) {
            schedule = NEXT.incrementAndGet();
            jdbc.update(
                    "INSERT INTO REPORT_SCHEDULE (ID, TEAM_ID, RUN_WEEKDAY, RUN_HOUR, NEXT_RUN_AT)"
                            + " VALUES (?, ?, 1, 6, ?)",
                    schedule,
                    team,
                    Timestamp.from(now));
        }
        long id = NEXT.incrementAndGet();
        jdbc.update(
                "INSERT INTO REPORT_RUN (ID, SCHEDULE_ID, TEAM_ID, REPORT, RAN_AT, AS_OF, LINE_COUNT, HTML, CSV)"
                        + " VALUES (?, ?, ?, 'AGING', ?, ?, 0, 'html', 'csv')",
                id,
                schedule,
                team,
                Timestamp.from(ranAt),
                Date.valueOf(LocalDate.ofInstant(ranAt, ZoneOffset.UTC)));
        mail(null, id);
        return id;
    }

    long contentRows(long caseId) {
        long rows = 0;
        for (String table : CONTENT) {
            rows += count("SELECT COUNT(*) FROM " + table + " WHERE CASE_ID = ?", caseId);
        }
        return rows
                + count(
                        "SELECT COUNT(*) FROM CASE_ATTACHMENT_CHUNK WHERE ATTACHMENT_ID IN"
                                + " (SELECT ID FROM CASE_ATTACHMENT WHERE CASE_ID = ?)",
                        caseId);
    }

    long historyRows(long caseId) {
        long rows = 0;
        for (String table : List.of("AUDIT_EVENT", "ALERT_LEDGER", "LEGAL_HOLD_EVENT", "USER_ALERT")) {
            rows += count("SELECT COUNT(*) FROM " + table + " WHERE CASE_ID = ?", caseId);
        }
        return rows;
    }

    long count(String sql, Object arg) {
        Long count = jdbc.queryForObject(sql, Long.class, arg);
        return count == null ? 0 : count;
    }

    /** Removes every row written here, and what the purge and the application wrote about these cases. */
    void remove() {
        for (String table : List.of(
                "CASE_COMMENT_PIN",
                "UPDATE_REQUEST",
                "CASE_COMMENT",
                "CASE_IMPORTED_EVENT",
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
                "DELETE FROM NOTIFICATION_OUTBOX WHERE REPORT_RUN_ID IN (SELECT ID FROM REPORT_RUN WHERE TEAM_ID = ?)",
                team);
        jdbc.update("DELETE FROM REPORT_RUN WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM REPORT_SCHEDULE WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM IMPORT_RUN WHERE ID = ?", importRun);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
        jdbc.update("DELETE FROM APP_USER WHERE ID IN (?, ?)", user, approver);
    }
}
