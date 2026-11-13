// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e.security;

import io.trailkeep.casefile.CaseFields;
import io.trailkeep.casefile.CasePriority;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.closure.ClosureDecision;
import io.trailkeep.closure.ClosureDecisionService;
import io.trailkeep.closure.ClosureService;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Committed data in which every page the application maps has something real to show, so a crawl over the GET routes
 * sees each page in its ordinary state rather than as an error: one team with {@code alpha.analyst} as Analyst and
 * {@code alpha.manager1} as Manager; an open case of the Analyst's with a clean and a quarantined file, a comment and
 * an update request asked of them; a case pending closure with the Manager as approver; a closed case; a restricted
 * case of the Analyst's; a stored report run and an import run. {@link #value} fills a route's pattern variables.
 */
final class PageFixture {

    private static final long RUN = 952_001L;
    private static final long IMPORT_RUN = 952_002L;
    private static final long UPDATE_REQUEST = 952_003L;
    private static final String OF_TEAM = " WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = ?)";

    private final JdbcTemplate jdbc;
    private final CaseSeed seed;
    private final long approver;
    private final long caseType;

    /** The fixture's team. */
    final long team;

    /** The Analyst's open case. */
    final CaseNumber open;

    /** The Analyst's restricted case. */
    final CaseNumber restricted;

    private final CaseNumber pending;
    private final CaseNumber closed;
    private final long cleanFile;
    private final long quarantinedFile;
    private final long closure;

    PageFixture(
            JdbcTemplate jdbc,
            CaseService cases,
            ClosureService closures,
            ClosureDecisionService decisions,
            long analyst,
            long manager) {
        this.jdbc = jdbc;
        this.seed = new CaseSeed(jdbc);
        this.team = seed.team();
        this.caseType = seed.caseType();
        this.approver = seed.user();
        seed.member(analyst, team, TeamRole.ANALYST);
        seed.member(manager, team, TeamRole.MANAGER);
        seed.member(approver, team, TeamRole.MANAGER);
        jdbc.update(
                "INSERT INTO CASE_TYPE_OUTCOME (CASE_TYPE_ID, OUTCOME, SORT_ORDER) VALUES (?, 'Resolved', 1)",
                caseType);
        Actor owner = actor(analyst, TeamRole.ANALYST);
        this.open = cases.create(owner, fields(analyst, "Synthetic crawl case", Sensitivity.STANDARD));
        this.restricted =
                cases.create(owner, fields(analyst, "Synthetic restricted crawl case", Sensitivity.RESTRICTED));
        this.pending = cases.create(owner, fields(analyst, "Synthetic pending crawl case", Sensitivity.STANDARD));
        this.closed = cases.create(owner, fields(analyst, "Synthetic closed crawl case", Sensitivity.STANDARD));
        long openId = seed.caseId(open);
        this.cleanFile = file(openId, analyst, "synthetic-clean.txt", "CLEAN");
        this.quarantinedFile = file(openId, analyst, "synthetic-quarantined.txt", "QUARANTINED");
        seed.comment(openId, analyst, "Synthetic **comment**");
        jdbc.update(
                "INSERT INTO UPDATE_REQUEST (ID, CASE_ID, REQUESTED_BY, REQUESTED_FROM, MESSAGE, REQUESTED_AT,"
                        + " OPEN_KEY) VALUES (?, ?, ?, ?, 'Synthetic question', ?, ?)",
                UPDATE_REQUEST,
                openId,
                manager,
                analyst,
                Timestamp.from(Instant.now()),
                openId + ":" + analyst);
        this.closure =
                closures.requestClosure(owner, pending.toString(), "Resolved", "Synthetic summary", null, manager);
        long done = closures.requestClosure(owner, closed.toString(), "Resolved", "Synthetic summary", null, approver);
        decisions.decide(actor(approver, TeamRole.MANAGER), closed.toString(), done, ClosureDecision.APPROVED, null);
        storedRunAndImportRun(analyst);
    }

    /**
     * The value of a pattern variable for the page a route shows.
     *
     * @param pattern the route's pattern, which decides what a variable names
     * @param variable the variable's name
     * @return the value
     */
    String value(String pattern, String variable) {
        return switch (variable) {
            case "number" -> caseFor(pattern).toString();
            case "team", "teamId" -> Long.toString(team);
            case "id" -> Long.toString(idFor(pattern));
            default -> throw unknown(pattern, variable);
        };
    }

    /** Removes everything this fixture committed, and what the crawl's page views wrote about it, as the owner. */
    void remove() {
        jdbc.update("DELETE FROM REPORT_RUN WHERE ID = ?", RUN);
        jdbc.update("DELETE FROM REPORT_SCHEDULE WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM IMPORT_RUN WHERE ID = ?", IMPORT_RUN);
        jdbc.update(
                "DELETE FROM CASE_ATTACHMENT_CHUNK WHERE ATTACHMENT_ID IN (SELECT ID FROM CASE_ATTACHMENT" + OF_TEAM
                        + ")",
                team);
        for (String table : new String[] {
            "CASE_ATTACHMENT",
            "CASE_COMMENT",
            "UPDATE_REQUEST",
            "CASE_REOPEN",
            "CLOSURE_REQUEST",
            "NOTIFICATION_OUTBOX",
            "USER_ALERT",
            "AUDIT_EVENT",
            "CASE_SEARCH",
            "CASE_SLA"
        }) {
            jdbc.update("DELETE FROM " + table + OF_TEAM, team);
        }
        jdbc.update("DELETE FROM CASE_RECORD WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM APP_USER WHERE ID = ?", approver);
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE_OUTCOME WHERE CASE_TYPE_ID = ?", caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    private CaseNumber caseFor(String pattern) {
        if (pattern.endsWith("/reopen")) {
            return closed;
        }
        return pattern.contains("/closure/") ? pending : open;
    }

    private long idFor(String pattern) {
        if (pattern.contains("/attachments/{id}")) {
            return pattern.endsWith("/release") ? quarantinedFile : cleanFile;
        }
        if (pattern.contains("/closure/{id}")) {
            return closure;
        }
        if (pattern.startsWith("/reports/runs/")) {
            return RUN;
        }
        if (pattern.startsWith("/admin/import-runs/")) {
            return IMPORT_RUN;
        }
        if (pattern.startsWith("/admin/case-types/")) {
            return caseType;
        }
        if (pattern.startsWith("/admin/sla-policies/")) {
            return Objects.requireNonNull(
                    jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType));
        }
        throw unknown(pattern, "id");
    }

    private static IllegalStateException unknown(String pattern, String variable) {
        return new IllegalStateException(
                "The crawl cannot fill {" + variable + "} in " + pattern + "; teach PageFixture.value what it names");
    }

    private long file(long caseId, long uploader, String name, String status) {
        long id = seed.attachment(caseId, uploader, name, status);
        jdbc.update(
                "INSERT INTO CASE_ATTACHMENT_CHUNK (ATTACHMENT_ID, SEQ, CHUNK) VALUES (?, 0, ?)", id, new byte[] {'x'});
        return id;
    }

    private void storedRunAndImportRun(long requester) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                "INSERT INTO REPORT_SCHEDULE (ID, TEAM_ID, RUN_WEEKDAY, RUN_HOUR, NEXT_RUN_AT) VALUES (?, ?, 1, 6, ?)",
                RUN,
                team,
                now);
        jdbc.update(
                "INSERT INTO REPORT_RUN (ID, SCHEDULE_ID, TEAM_ID, REPORT, RAN_AT, AS_OF, LINE_COUNT, HTML, CSV)"
                        + " VALUES (?, ?, ?, 'AGING', ?, ?, 0, ?, ?)",
                RUN,
                RUN,
                team,
                now,
                Date.valueOf(LocalDate.now(ZoneOffset.UTC)),
                "<p>Synthetic stored run</p>",
                "\"Team\"\r\n");
        jdbc.update(
                "INSERT INTO IMPORT_RUN (ID, KIND, SOURCE_ID, STATUS, REQUESTED_BY, REQUESTED_AT, UPDATED_AT)"
                        + " VALUES (?, 'IMPORT', 'synthetic-crawl', 'DRY_RUN', ?, ?, ?)",
                IMPORT_RUN,
                requester,
                now,
                now);
    }

    private Actor actor(long id, TeamRole role) {
        return new Actor(new UserPermissions(id, false, Set.of(), Map.of(team, role)), "Synthetic Person " + id);
    }

    private CaseFields fields(long owner, String title, Sensitivity sensitivity) {
        return new CaseFields(
                caseType,
                title,
                CasePriority.NORMAL,
                LocalDate.now(ZoneOffset.UTC).minusDays(2),
                team,
                owner,
                "Synthetic subject",
                "Synthetic description",
                sensitivity);
    }
}
