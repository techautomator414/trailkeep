// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e.security;

import io.trailkeep.casefile.CaseFields;
import io.trailkeep.casefile.CasePriority;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Cases whose text carries markers a leak crawl looks for, committed for a real server and removed afterwards. Team A
 * has {@code alpha.reader} as Reader; team B has {@code beta.analyst} as Analyst. Team A holds a restricted case owned
 * by someone else whose title, subject, description, comment, attachment name, update request and an alert addressed
 * to the Reader all carry {@link #secret}, and a standard twin whose title, comment and attachment name carry
 * {@link #twinA}. Team B holds a standard case carrying {@link #twinB}. The Reader may read the twin and nothing
 * restricted; the Analyst of team B may read neither case of team A. The markers are fresh for each run.
 */
final class LeakFixture {

    private static final long ALERT = 953_001L;
    private static final long UPDATE_REQUEST = 953_002L;
    private static final String OF_TEAM = " WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = ?)";

    /** In every text of the restricted case. */
    final String secret = marker("restricted");

    /** In the texts of team A's standard case. */
    final String twinA = marker("alpha");

    /** In the texts of team B's standard case. */
    final String twinB = marker("beta");

    /** The restricted case. */
    final CaseNumber restricted;

    private final JdbcTemplate jdbc;
    private final CaseSeed seed;
    private final long teamA;
    private final long teamB;
    private final long owner;
    private final long caseType;

    LeakFixture(JdbcTemplate jdbc, CaseService cases, long reader, long betaAnalyst) {
        this.jdbc = jdbc;
        this.seed = new CaseSeed(jdbc);
        this.teamA = seed.team();
        this.teamB = seed.team();
        this.caseType = seed.caseType();
        this.owner = seed.user();
        seed.member(reader, teamA, TeamRole.READER);
        seed.member(owner, teamA, TeamRole.ANALYST);
        seed.member(betaAnalyst, teamB, TeamRole.ANALYST);
        this.restricted = cases.create(actor(owner, teamA), fields(teamA, owner, secret, Sensitivity.RESTRICTED));
        CaseNumber twin = cases.create(actor(owner, teamA), fields(teamA, owner, twinA, Sensitivity.STANDARD));
        cases.create(actor(betaAnalyst, teamB), fields(teamB, betaAnalyst, twinB, Sensitivity.STANDARD));
        long restrictedId = seed.caseId(restricted);
        long twinId = seed.caseId(twin);
        final Timestamp now = Timestamp.from(Instant.now());
        seed.comment(restrictedId, owner, "Synthetic comment " + secret);
        seed.comment(twinId, owner, "Synthetic comment " + twinA);
        for (long file : new long[] {
            seed.attachment(restrictedId, owner, secret + ".txt", "CLEAN"),
            seed.attachment(twinId, owner, twinA + ".txt", "CLEAN")
        }) {
            jdbc.update(
                    "INSERT INTO CASE_ATTACHMENT_CHUNK (ATTACHMENT_ID, SEQ, CHUNK) VALUES (?, 0, ?)",
                    file,
                    new byte[] {'x'});
        }
        jdbc.update(
                "INSERT INTO UPDATE_REQUEST (ID, CASE_ID, REQUESTED_BY, REQUESTED_FROM, MESSAGE, REQUESTED_AT,"
                        + " OPEN_KEY) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UPDATE_REQUEST,
                restrictedId,
                owner,
                reader,
                "Synthetic question " + secret,
                now,
                restrictedId + ":" + reader);
        // As if the Reader had been told about the case before it was restricted.
        jdbc.update(
                "INSERT INTO USER_ALERT (ID, USER_ID, MESSAGE, LINK, CASE_ID, CREATED_AT) VALUES (?, ?, ?, ?, ?, ?)",
                ALERT,
                reader,
                "Synthetic alert " + secret,
                "/cases/" + restricted,
                restrictedId,
                now);
    }

    /** Removes everything this fixture committed, as the schema owner. */
    void remove() {
        for (long team : new long[] {teamA, teamB}) {
            jdbc.update(
                    "DELETE FROM CASE_ATTACHMENT_CHUNK WHERE ATTACHMENT_ID IN (SELECT ID FROM CASE_ATTACHMENT" + OF_TEAM
                            + ")",
                    team);
            for (String table : new String[] {
                "CASE_ATTACHMENT",
                "CASE_COMMENT",
                "UPDATE_REQUEST",
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
            jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        }
        jdbc.update("DELETE FROM APP_USER WHERE ID = ?", owner);
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    private static String marker(String what) {
        return "zq" + what + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static Actor actor(long user, long team) {
        return new Actor(new UserPermissions(user, false, Set.of(), Map.of(team, TeamRole.MANAGER)), "Synthetic Actor");
    }

    private CaseFields fields(long team, long caseOwner, String marker, Sensitivity sensitivity) {
        return new CaseFields(
                caseType,
                "Synthetic leak case " + marker,
                CasePriority.NORMAL,
                LocalDate.now(ZoneOffset.UTC).minusDays(2),
                team,
                caseOwner,
                "Synthetic subject " + marker,
                "Synthetic description " + marker,
                sensitivity);
    }
}
