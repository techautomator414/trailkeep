// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e.security;

import io.trailkeep.casefile.CaseFields;
import io.trailkeep.casefile.CasePriority;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The cases the route sweeps ask about, committed for a real server and removed afterwards. For a signed-in Analyst of
 * team A: their own case ({@link #mine}), a standard case of team B ({@link #otherTeam}), a restricted case of team A
 * that someone else owns ({@link #restricted}), and a well-formed number no case has ({@link #absent}). Each real case
 * has an attachment with one byte of content, an open update request and a pending closure request, so routes that
 * name one of those by id get a real id of that very case.
 */
final class SweepCases {

    /** Ids far above the pooled sequences and {@link CaseSeed}'s range, so seeded rows never collide. */
    private static final AtomicLong NEXT = new AtomicLong(950_000);

    private final JdbcTemplate jdbc;
    private final CaseService cases;
    private final CaseSeed seed;
    private final List<Long> teams = new ArrayList<>();
    private final List<Long> users = new ArrayList<>();

    /** The case the signed-in Analyst owns. */
    final Target mine;

    /** A standard case of a team the Analyst is not in. */
    final Target otherTeam;

    /** A restricted case of the Analyst's own team, owned by another Analyst. */
    final Target restricted;

    /** A case number that is well formed and names no case, with the children of {@link #mine}. */
    final Target absent;

    /**
     * A case as a route sweep fills a pattern with it.
     *
     * @param label what the case is, for messages
     * @param number its case number
     * @param attachment an attachment id
     * @param updateRequest an update request id
     * @param closure a closure request id
     */
    record Target(String label, String number, long attachment, long updateRequest, long closure) {

        /**
         * The value of a pattern variable for this case.
         *
         * @param pattern the route's pattern, which decides what an {@code id} names
         * @param variable the variable's name
         * @return the value
         */
        String value(String pattern, String variable) {
            if ("number".equals(variable)) {
                return number;
            }
            if ("id".equals(variable)) {
                if (pattern.contains("/attachments/{id}")) {
                    return Long.toString(attachment);
                }
                if (pattern.contains("/update-requests/{id}")) {
                    return Long.toString(updateRequest);
                }
                if (pattern.contains("/closure/{id}")) {
                    return Long.toString(closure);
                }
            }
            throw new IllegalStateException("The sweep cannot fill {" + variable + "} in " + pattern
                    + "; teach SweepCases.Target.value what it names");
        }
    }

    SweepCases(JdbcTemplate jdbc, CaseService cases, long analyst) {
        this.jdbc = jdbc;
        this.cases = cases;
        this.seed = new CaseSeed(jdbc);
        long teamA = team();
        long teamB = team();
        long managerA = user();
        long colleague = user();
        long managerB = user();
        long ownerB = user();
        seed.member(analyst, teamA, TeamRole.ANALYST);
        seed.member(colleague, teamA, TeamRole.ANALYST);
        seed.member(managerA, teamA, TeamRole.MANAGER);
        seed.member(ownerB, teamB, TeamRole.ANALYST);
        seed.member(managerB, teamB, TeamRole.MANAGER);
        this.mine = target("own case", teamA, analyst, managerA, Sensitivity.STANDARD);
        this.otherTeam = target("another team's case", teamB, ownerB, managerB, Sensitivity.STANDARD);
        this.restricted = target("restricted case", teamA, colleague, managerA, Sensitivity.RESTRICTED);
        String number = mine.number();
        this.absent = new Target(
                "absent case",
                number.substring(0, number.lastIndexOf('-') + 1) + "999999",
                mine.attachment(),
                mine.updateRequest(),
                mine.closure());
    }

    /** The cases a signed-in Analyst may not read, and the number no case has. */
    List<Target> unreadable() {
        return List.of(absent, otherTeam, restricted);
    }

    /** Every audit row; a request refused before it reaches a service writes none. */
    long auditRows() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM AUDIT_EVENT", Long.class));
    }

    /** Removes everything this fixture committed, as the schema owner. */
    void remove() {
        String ofTeam = " WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = ?)";
        for (long team : teams) {
            jdbc.update("DELETE FROM AUDIT_EVENT" + ofTeam, team);
            jdbc.update("DELETE FROM UPDATE_REQUEST" + ofTeam, team);
            jdbc.update("DELETE FROM CLOSURE_REQUEST" + ofTeam, team);
            jdbc.update(
                    "DELETE FROM CASE_ATTACHMENT_CHUNK WHERE ATTACHMENT_ID IN (SELECT ID FROM CASE_ATTACHMENT" + ofTeam
                            + ")",
                    team);
            jdbc.update("DELETE FROM CASE_ATTACHMENT" + ofTeam, team);
            jdbc.update("DELETE FROM CASE_SEARCH" + ofTeam, team);
            jdbc.update("DELETE FROM CASE_SLA" + ofTeam, team);
            jdbc.update("DELETE FROM CASE_RECORD WHERE TEAM_ID = ?", team);
            jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", team);
            jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        }
        users.forEach(user -> jdbc.update("DELETE FROM APP_USER WHERE ID = ?", user));
        long type = seed.caseType();
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, type);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", type);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    private long team() {
        long team = seed.team();
        teams.add(team);
        return team;
    }

    private long user() {
        long user = seed.user();
        users.add(user);
        return user;
    }

    private Target target(String label, long team, long owner, long manager, Sensitivity sensitivity) {
        CaseService.Actor actor = new CaseService.Actor(
                new UserPermissions(owner, false, Set.of(), Map.of(team, TeamRole.MANAGER)), "Synthetic Actor");
        CaseNumber number = cases.create(
                actor,
                new CaseFields(
                        seed.caseType(),
                        "Synthetic sweep case",
                        CasePriority.NORMAL,
                        LocalDate.now(ZoneOffset.UTC).minusDays(2),
                        team,
                        owner,
                        "Synthetic subject",
                        "Synthetic description",
                        sensitivity));
        long caseId = seed.caseId(number);
        long attachment = seed.attachment(caseId, owner, "synthetic.txt", "CLEAN");
        jdbc.update(
                "INSERT INTO CASE_ATTACHMENT_CHUNK (ATTACHMENT_ID, SEQ, CHUNK) VALUES (?, 0, ?)",
                attachment,
                new byte[] {'x'});
        Timestamp now = Timestamp.from(Instant.now());
        long updateRequest = NEXT.incrementAndGet();
        jdbc.update(
                "INSERT INTO UPDATE_REQUEST (ID, CASE_ID, REQUESTED_BY, REQUESTED_FROM, MESSAGE, REQUESTED_AT,"
                        + " OPEN_KEY) VALUES (?, ?, ?, ?, 'Synthetic question', ?, ?)",
                updateRequest,
                caseId,
                manager,
                owner,
                now,
                caseId + ":" + owner);
        long closure = NEXT.incrementAndGet();
        jdbc.update(
                "INSERT INTO CLOSURE_REQUEST (ID, CASE_ID, REQUESTED_BY, APPROVER_ID, REQUESTED_AT, OUTCOME,"
                        + " RESOLUTION_SUMMARY) VALUES (?, ?, ?, ?, ?, 'Resolved', 'Synthetic summary')",
                closure,
                caseId,
                owner,
                manager,
                now);
        return new Target(label, number.toString(), attachment, updateRequest, closure);
    }
}
