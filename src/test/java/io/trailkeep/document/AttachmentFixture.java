// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Committed rows for the download and release ITs, whose downloads stream in a transaction of their own and so cannot
 * see a test's uncommitted seed. Users {@code base + 1..5} (Analyst, Reader and Manager of the team, a Compliance
 * Admin, a Manager of another team), teams {@code base + 100} and {@code base + 101}, and three open cases: {@link
 * #number} and {@link #sibling} of the team, and {@link #foreign} of the other team. {@link #remove()} deletes it all
 * as the schema owner, because the application user may not delete attachments or audit events.
 */
final class AttachmentFixture {

    private static final AtomicLong NEXT_ATTACHMENT = new AtomicLong(963_000);

    final long analyst;
    final long reader;
    final long manager;
    final long admin;
    final long otherManager;
    final long team;
    final long otherTeam;
    final CaseNumber number;
    final CaseNumber sibling;
    final CaseNumber foreign;

    private final JdbcTemplate jdbc;
    private final AttachmentContentJdbcDao content;
    private final CaseSeed seed;

    AttachmentFixture(JdbcTemplate jdbc, AttachmentContentJdbcDao content, long base) {
        this.jdbc = jdbc;
        this.content = content;
        this.seed = new CaseSeed(jdbc);
        analyst = base + 1;
        reader = base + 2;
        manager = base + 3;
        admin = base + 4;
        otherManager = base + 5;
        team = base + 100;
        otherTeam = base + 101;
        user(analyst, "Synthetic Attachment Analyst");
        user(reader, "Synthetic Attachment Reader");
        user(manager, "Synthetic Attachment Manager");
        user(admin, "Synthetic Compliance Admin");
        user(otherManager, "Synthetic Other Manager");
        jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, 'COMPLIANCE_ADMIN')", admin);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, ?)", team, "Synthetic Attachment Team " + base);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, ?)", otherTeam, "Synthetic Other Team " + base);
        seed.member(analyst, team, TeamRole.ANALYST);
        seed.member(reader, team, TeamRole.READER);
        seed.member(manager, team, TeamRole.MANAGER);
        seed.member(otherManager, otherTeam, TeamRole.MANAGER);
        number = seed.caseRow(team, analyst, Sensitivity.STANDARD, CaseStatus.OPEN);
        sibling = seed.caseRow(team, analyst, Sensitivity.STANDARD, CaseStatus.OPEN);
        foreign = seed.caseRow(otherTeam, otherManager, Sensitivity.STANDARD, CaseStatus.OPEN);
    }

    /**
     * A new open RESTRICTED case of the team, owned by the fixture's Analyst; a Manager of the team reads it, a Reader
     * does not. Removed with the rest of the team's cases.
     *
     * @return its number
     */
    CaseNumber restrictedCase() {
        return seed.caseRow(team, analyst, Sensitivity.RESTRICTED, CaseStatus.OPEN);
    }

    /**
     * The case's database id.
     *
     * @param on the case
     * @return the id
     */
    long caseId(CaseNumber on) {
        return seed.caseId(on);
    }

    /**
     * Stores an attachment with its content in chunks, as an upload would.
     *
     * @param on the case
     * @param fileName the name as uploaded
     * @param status the stored status; a quarantined one carries a synthetic signature
     * @param bytes the content
     * @return the attachment's id
     */
    long attachment(CaseNumber on, String fileName, ScanStatus status, byte[] bytes) {
        return attachment(on, fileName, status, bytes.length, sha256(bytes), new ByteArrayInputStream(bytes));
    }

    /**
     * Stores an attachment whose content is read from a stream, one chunk in memory at a time.
     *
     * @param on the case
     * @param fileName the name as uploaded
     * @param status the stored status; a quarantined one carries a synthetic signature
     * @param size the content's length
     * @param sha256 the content's hash
     * @param bytes the content
     * @return the attachment's id
     */
    long attachment(CaseNumber on, String fileName, ScanStatus status, long size, String sha256, InputStream bytes) {
        long id = NEXT_ATTACHMENT.incrementAndGet();
        jdbc.update(
                "INSERT INTO CASE_ATTACHMENT (ID, CASE_ID, FILE_NAME, CONTENT_TYPE, SIZE_BYTES, SHA256, SCAN_STATUS,"
                        + " SCAN_DETAIL, UPLOADED_AT, UPLOADED_BY) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                seed.caseId(on),
                fileName,
                "text/plain",
                size,
                sha256,
                status.name(),
                status == ScanStatus.QUARANTINED ? "Synthetic.Test.Signature" : null,
                Timestamp.from(Instant.parse("2026-11-05T09:00:00Z")),
                analyst);
        try {
            content.write(id, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return id;
    }

    /**
     * The actor a user is to the services.
     *
     * @param userId one of the fixture's users
     * @return the actor, with the roles seeded for it
     */
    Actor actor(long userId) {
        Set<GlobalRole> global = userId == admin ? Set.of(GlobalRole.COMPLIANCE_ADMIN) : Set.of();
        Map<Long, TeamRole> roles =
                userId == admin ? Map.of() : Map.of(userId == otherManager ? otherTeam : team, role(userId));
        return new Actor(new UserPermissions(userId, false, global, roles), "Synthetic Actor " + userId);
    }

    /** Deletes everything the fixture and the tests wrote. */
    void remove() {
        String ofTeams = " WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID IN (?, ?))";
        jdbc.update(
                "DELETE FROM CASE_ATTACHMENT_CHUNK WHERE ATTACHMENT_ID IN (SELECT ID FROM CASE_ATTACHMENT" + ofTeams
                        + ")",
                team,
                otherTeam);
        jdbc.update("DELETE FROM CASE_ATTACHMENT" + ofTeams, team, otherTeam);
        jdbc.update("DELETE FROM AUDIT_EVENT" + ofTeams, team, otherTeam);
        jdbc.update("DELETE FROM CASE_RECORD WHERE TEAM_ID IN (?, ?)", team, otherTeam);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID IN (?, ?)", team, otherTeam);
        jdbc.update("DELETE FROM TEAM WHERE ID IN (?, ?)", team, otherTeam);
        long caseType = seed.caseType();
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
        jdbc.update("DELETE FROM APP_USER_GLOBAL_ROLE WHERE USER_ID = ?", admin);
        jdbc.update("DELETE FROM APP_USER WHERE ID BETWEEN ? AND ?", analyst, otherManager);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private TeamRole role(long userId) {
        if (userId == analyst) {
            return TeamRole.ANALYST;
        }
        return userId == reader ? TeamRole.READER : TeamRole.MANAGER;
    }

    private void user(long id, String name) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, ?, FALSE, CURRENT_TIMESTAMP)",
                id,
                "attachment-subject-" + id,
                name,
                "user" + id + "@example.invalid");
    }
}
