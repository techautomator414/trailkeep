// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.LegalHoldService.LegalHold;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserDirectoryService;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Legal hold, once per vendor (WP-24 part b): a Compliance Admin places a hold with a reason, which is stored with who
 * and when, shown only to Compliance Admins, audited with the reason redacted, and keeps the archive job off the case;
 * lifting it clears all of that, is audited, lets the case be archived again, and gives an archived case at least the
 * purge grace before it may be purged. A Manager is {@code FORBIDDEN}, an unknown case {@code NOT_FOUND}, a blank or
 * over-long reason {@code INVALID}, and placing a hold twice or lifting none {@code CONFLICT}, with nothing audited.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class AbstractLegalHoldIT {

    private static final String REASON = "Synthetic litigation hold reason";

    @Autowired
    private LegalHoldService holds;

    @Autowired
    private CaseRetentionService retention;

    @Autowired
    private UserDirectoryService directory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private InstantSource clock;

    private long admin;
    private long manager;
    private long caseId;
    private String number = "";

    @BeforeEach
    void seed() {
        CaseSeed seed = new CaseSeed(jdbc);
        admin = seed.user();
        manager = seed.user();
        long team = seed.team();
        jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, ?)", admin, "COMPLIANCE_ADMIN");
        seed.member(manager, team, TeamRole.MANAGER);
        CaseNumber caseNumber = seed.caseRow(team, manager, Sensitivity.STANDARD, CaseStatus.CLOSED);
        number = caseNumber.toString();
        caseId = seed.caseId(caseNumber);
        jdbc.update("UPDATE CASE_TYPE SET RETENTION_YEARS = 0 WHERE ID = ?", seed.caseType());
        jdbc.update(
                "UPDATE CASE_RECORD SET CLOSED_AT = ? WHERE ID = ?",
                Timestamp.from(clock.instant().minus(Duration.ofDays(1))),
                caseId);
    }

    @Test
    void holdIsPlacedShownToComplianceAdminsKeptFromArchiveAndLifted() {
        final Instant before = clock.instant();
        assertThat(retention.dueForArchive(clock.instant())).contains(caseId);

        holds.change(actor(admin), number, true, "  " + REASON + "\r\n");
        flushAndClear();

        Map<String, Object> row = row();
        assertThat(row.get("LEGAL_HOLD")).isIn(true, 1, (short) 1, java.math.BigDecimal.ONE);
        assertThat(((Number) Objects.requireNonNull(row.get("LEGAL_HOLD_BY"))).longValue())
                .isEqualTo(admin);
        assertThat(row.get("LEGAL_HOLD_REASON")).isEqualTo(REASON);
        LegalHold seen = holds.current(actor(admin), number).orElseThrow();
        assertThat(seen.reason()).isEqualTo(REASON);
        assertThat(seen.heldAt()).isAfterOrEqualTo(before.minusMillis(1));
        assertThat(holds.current(actor(manager), number).orElseThrow().reason()).isEmpty();
        assertThat(retention.dueForArchive(clock.instant())).doesNotContain(caseId);
        assertThat(audits("LEGAL_HOLD_PLACED"))
                .hasSize(1)
                .allSatisfy(
                        json -> assertThat(json).contains("\"legalHold\":true").doesNotContain(REASON));

        holds.change(actor(admin), number, false, "Synthetic matter settled");
        flushAndClear();

        assertThat(holds.current(actor(admin), number)).isEmpty();
        assertThat(row().get("LEGAL_HOLD_REASON")).isNull();
        assertThat(row().get("LEGAL_HOLD_BY")).isNull();
        assertThat(retention.dueForArchive(clock.instant())).contains(caseId);
        assertThat(audits("LEGAL_HOLD_LIFTED"))
                .hasSize(1)
                .allSatisfy(
                        json -> assertThat(json).contains("\"legalHold\":false").doesNotContain("settled"));
    }

    @Test
    void liftingTheHoldOfAnArchivedCaseKeepsItThePurgeGraceLonger() {
        jdbc.update(
                "UPDATE CASE_RECORD SET STATUS = 'ARCHIVED', ARCHIVED_AT = ?, PURGE_AFTER = ?, LEGAL_HOLD = ?,"
                        + " LEGAL_HOLD_BY = ?, LEGAL_HOLD_AT = ?, LEGAL_HOLD_REASON = ? WHERE ID = ?",
                Timestamp.from(clock.instant().minus(Duration.ofDays(400))),
                Timestamp.from(clock.instant().minus(Duration.ofDays(370))),
                true,
                admin,
                Timestamp.from(clock.instant().minus(Duration.ofDays(380))),
                REASON,
                caseId);
        final Instant before = clock.instant();

        holds.change(actor(admin), number, false, "Synthetic matter settled");
        flushAndClear();

        Instant purgeAfter = Objects.requireNonNull(jdbc.queryForObject(
                        "SELECT PURGE_AFTER FROM CASE_RECORD WHERE ID = ?", Timestamp.class, caseId))
                .toInstant();
        assertThat(purgeAfter)
                .isBetween(before.plus(Duration.ofDays(30)), clock.instant().plus(Duration.ofDays(30)));
        assertThat(row().get("STATUS")).isEqualTo("ARCHIVED");
    }

    @Test
    void refusalsChangeNothingAndAuditNothing() {
        assertRefused(Kind.FORBIDDEN, () -> holds.change(actor(manager), number, true, REASON));
        assertRefused(Kind.NOT_FOUND, () -> holds.change(actor(admin), "SEED-2026-999999999", true, REASON));
        assertRefused(Kind.NOT_FOUND, () -> holds.change(actor(admin), "not a number", true, REASON));
        assertRefused(Kind.INVALID, () -> holds.change(actor(admin), number, true, " \r\n "));
        assertRefused(Kind.INVALID, () -> holds.change(actor(admin), number, true, "x".repeat(501)));
        assertRefused(Kind.CONFLICT, () -> holds.change(actor(admin), number, false, REASON));
        holds.change(actor(admin), number, true, "x".repeat(500));
        assertRefused(Kind.CONFLICT, () -> holds.change(actor(admin), number, true, REASON));
        flushAndClear();
        assertThat(audits("LEGAL_HOLD_PLACED")).hasSize(1);
        assertThat(audits("LEGAL_HOLD_LIFTED")).isEmpty();
        assertThat(directory.permissionsOf(admin).has(GlobalRole.COMPLIANCE_ADMIN))
                .isTrue();
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap(
                "SELECT STATUS, LEGAL_HOLD, LEGAL_HOLD_BY, LEGAL_HOLD_REASON FROM CASE_RECORD WHERE ID = ?", caseId);
    }

    private List<String> audits(String action) {
        return jdbc.queryForList(
                "SELECT NEW_VALUES_JSON FROM AUDIT_EVENT WHERE ACTION = ? AND CASE_ID = ? AND ACTOR_ID = ?",
                String.class,
                action,
                caseId,
                admin);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private Actor actor(long user) {
        return new Actor(directory.permissionsOf(user), "Synthetic actor");
    }

    private static void assertRefused(Kind kind, ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        CaseException.class, e -> assertThat(e.kind()).isEqualTo(kind));
    }
}
