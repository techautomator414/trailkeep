// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.testsupport;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.user.TeamRole;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Synthetic users, teams, a case type and cases written straight to the tables, for tests that need case rows before
 * the create flow exists. Ids start far above anything the pooled sequences hand out in a test run; use it inside a
 * rolled-back transaction.
 */
public final class CaseSeed {

    private static final AtomicLong NEXT = new AtomicLong(900_000);
    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-11-04T12:00:00Z"));

    private final JdbcTemplate jdbc;
    private long caseTypeId;

    public CaseSeed(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts an enabled user.
     *
     * @return the new user's id
     */
    public long user() {
        long id = NEXT.incrementAndGet();
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, CREATED_AT) VALUES (?, ?, ?, ?, ?)",
                id,
                "seed-" + id,
                "Seed User " + id,
                "seed" + id + "@example.test",
                NOW);
        return id;
    }

    /**
     * Inserts an active team.
     *
     * @return the new team's id
     */
    public long team() {
        long id = NEXT.incrementAndGet();
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, ?)", id, "Seed Team " + id);
        return id;
    }

    /**
     * Inserts an open-shaped case row with the given facts.
     *
     * @param teamId the owning team
     * @param ownerId the owner, also recorded as creator
     * @param sensitivity the sensitivity
     * @param status the status
     * @return the case number
     */
    public CaseNumber caseRow(long teamId, long ownerId, Sensitivity sensitivity, CaseStatus status) {
        long id = NEXT.incrementAndGet();
        CaseNumber number = new CaseNumber("SEED", 2026, id);
        jdbc.update(
                "INSERT INTO CASE_RECORD (ID, CASE_NUMBER, TITLE, DESCRIPTION, CASE_TYPE_ID, TEAM_ID, OWNER_ID,"
                        + " STATUS, SENSITIVITY, IDENTIFIED_ON, OPENED_AT, CREATED_AT, CREATED_BY, UPDATED_AT,"
                        + " UPDATED_BY) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                number.toString(),
                "Seed case " + id,
                "Synthetic description",
                caseType(),
                teamId,
                ownerId,
                status.name(),
                sensitivity.name(),
                Date.valueOf(LocalDate.of(2026, 11, 1)),
                NOW,
                NOW,
                ownerId,
                NOW,
                ownerId);
        return number;
    }

    /**
     * Looks up a seeded case's id.
     *
     * @param number the case number
     * @return the id of the case row with this number
     */
    public long caseId(CaseNumber number) {
        Long id =
                jdbc.queryForObject("SELECT ID FROM CASE_RECORD WHERE CASE_NUMBER = ?", Long.class, number.toString());
        return id == null ? -1 : id;
    }

    /**
     * Inserts a comment written by a seeded user.
     *
     * @param caseId the case
     * @param authorId the author, whose seeded display name becomes the snapshot
     * @param body the Markdown body
     * @return the new comment's id
     */
    public long comment(long caseId, long authorId, String body) {
        long id = NEXT.incrementAndGet();
        jdbc.update(
                "INSERT INTO CASE_COMMENT (ID, CASE_ID, BODY, AUTHOR_ID, AUTHOR_NAME_SNAPSHOT, CREATED_AT)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                id,
                caseId,
                body,
                authorId,
                "Seed User " + authorId,
                NOW);
        return id;
    }

    /**
     * Gives a user a role in a team.
     *
     * @param userId the user
     * @param teamId the team
     * @param role the role in the team
     */
    public void member(long userId, long teamId, TeamRole role) {
        jdbc.update(
                "INSERT INTO APP_USER_TEAM (USER_ID, TEAM_ID, ROLE_IN_TEAM) VALUES (?, ?, ?)",
                userId,
                teamId,
                role.name());
    }

    /**
     * The seed's active case type, created on first use with a 30-day policy anchored on the identified-on day at
     * 80 % at risk (the column defaults).
     *
     * @return the case type's id
     */
    public long caseType() {
        if (caseTypeId == 0) {
            long policy = NEXT.incrementAndGet();
            jdbc.update(
                    "INSERT INTO SLA_POLICY (ID, NAME, TARGET_DAYS) VALUES (?, ?, ?)", policy, "Seed " + policy, 30);
            caseTypeId = NEXT.incrementAndGet();
            jdbc.update(
                    "INSERT INTO CASE_TYPE (ID, NAME, SLA_POLICY_ID) VALUES (?, ?, ?)",
                    caseTypeId,
                    "Seed " + caseTypeId,
                    policy);
        }
        return caseTypeId;
    }
}
