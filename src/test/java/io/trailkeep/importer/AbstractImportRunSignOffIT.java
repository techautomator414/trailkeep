// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The run request and the sign-off (WP-26 part s2, design §3 steps 0 and 4), once per vendor. A System or Compliance
 * Admin requests a run naming a well-formed source id, audited with it; a malformed one is refused and stores nothing,
 * and a team Manager may not request. A Compliance Admin, the requester included, signs off a completed dry run with no
 * FATAL issue once every WARNING class is acknowledged: only the sign-off columns and the version change, the run reads
 * as approved and the sign-off is audited with the recorded scanning mode. Everything is checked again on the server: a
 * missing acknowledgement is refused, and so are a stale version, a FATAL issue, a run that is not a completed dry run
 * or is signed off already, a System Admin and an unknown run, with nothing stored. Rolled back after each test; the
 * persistence context is cleared after each change made behind its back.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class AbstractImportRunSignOffIT {

    private static final long RUN = 989_501L;
    private static final Instant T = Instant.parse("2026-11-12T09:00:00Z");

    @Autowired
    private ImportRunService imports;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private long admin;

    @BeforeEach
    void seedADryRun() {
        admin = new CaseSeed(jdbc).user();
        jdbc.update(
                "INSERT INTO IMPORT_RUN (ID, KIND, SOURCE_ID, STATUS, REQUESTED_BY, REQUESTED_AT, SCAN_MODE,"
                        + " UPDATED_AT) VALUES (?, 'IMPORT', 'synthetic-b', 'DRY_RUN', ?, ?, 'EXTERNAL', ?)",
                RUN,
                admin,
                Timestamp.from(T),
                Timestamp.from(T));
        issue(1, "ATTACHMENT", "WARNING", "NOT_SCANNED");
        issue(2, "PERSON", "WARNING", "AMBIGUOUS_PERSON");
        issue(3, "PERSON", "WARNING", "AMBIGUOUS_PERSON");
        issue(4, "CASE", "ERROR", "TITLE_MISSING");
    }

    @Test
    void requestingOpensARequestedRunAuditedWithItsSource() {
        long id = imports.request(as(GlobalRole.SYSTEM_ADMIN), "legacy-a1", false);
        final long relink = imports.request(as(GlobalRole.COMPLIANCE_ADMIN), "legacy-a1", true);
        entityManager.flush();

        assertThat(jdbc.queryForList("SELECT KIND, SOURCE_ID, STATUS, REQUESTED_BY FROM IMPORT_RUN WHERE ID = ?", id))
                .singleElement()
                .satisfies(row -> assertThat(List.copyOf(row.values()).stream().map(String::valueOf))
                        .containsExactly("IMPORT", "legacy-a1", "REQUESTED", Long.toString(admin)));
        assertThat(audited(id, "IMPORT_RUN_REQUESTED")).contains("\"sourceId\":\"legacy-a1\"", "\"kind\":\"IMPORT\"");
        assertThat(jdbc.queryForObject("SELECT KIND FROM IMPORT_RUN WHERE ID = ?", String.class, relink))
                .as("a Compliance Admin's relink run (WP-26 part z2)")
                .isEqualTo("RELINK");
        assertThat(audited(relink, "IMPORT_RUN_REQUESTED")).contains("\"kind\":\"RELINK\"");
    }

    @Test
    void malformedSourceIdsAndTeamRolesRequestNothing() {
        final Long before = jdbc.queryForObject("SELECT COUNT(*) FROM IMPORT_RUN", Long.class);

        refused(() -> imports.request(as(GlobalRole.COMPLIANCE_ADMIN), "Legacy A", false), Kind.INVALID);
        refused(() -> imports.request(as(GlobalRole.COMPLIANCE_ADMIN), "a".repeat(17), true), Kind.INVALID);
        refused(() -> imports.request(as(GlobalRole.COMPLIANCE_ADMIN), "", false), Kind.INVALID);
        refused(() -> imports.request(as(GlobalRole.SYSTEM_ADMIN), "legacy-a1", true), Kind.FORBIDDEN);
        Actor manager = new Actor(new UserPermissions(admin, false, Set.of(), Map.of(1L, TeamRole.MANAGER)), "M");
        refused(() -> imports.request(manager, "legacy-a1", false), Kind.FORBIDDEN);
        refused(() -> imports.request(manager, "legacy-a1", true), Kind.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM IMPORT_RUN", Long.class))
                .isEqualTo(before);
    }

    @Test
    void theRequesterSignsOffAcknowledgingEveryWarningClass() {
        Actor compliance = as(GlobalRole.COMPLIANCE_ADMIN);
        assertThat(imports.run(compliance, RUN).signable()).isTrue();
        assertThat(imports.run(compliance, RUN).warnings()).containsExactly("AMBIGUOUS_PERSON", "NOT_SCANNED");

        imports.signOff(compliance, RUN, 0, Set.of("NOT_SCANNED", "AMBIGUOUS_PERSON", "NOT_A_WARNING"));
        entityManager.flush();

        assertThat(jdbc
                        .queryForMap(
                                "SELECT STATUS, APPROVED_BY, ACKNOWLEDGED_WARNINGS, VERSION, SCAN_MODE FROM IMPORT_RUN"
                                        + " WHERE ID = ?",
                                RUN)
                        .values()
                        .stream()
                        .map(String::valueOf))
                .containsExactly("DRY_RUN", Long.toString(admin), "AMBIGUOUS_PERSON,NOT_SCANNED", "1", "EXTERNAL");
        ImportRunService.RunDetail run = imports.run(compliance, RUN);
        assertThat(run.summary().status()).isEqualTo("APPROVED");
        assertThat(run.approvedBy()).isEqualTo(admin);
        assertThat(run.approvedAt()).isNotNull();
        assertThat(run.acknowledged()).containsExactly("AMBIGUOUS_PERSON", "NOT_SCANNED");
        assertThat(run.signable()).isFalse();
        assertThat(audited(RUN, "IMPORT_RUN_SIGNED_OFF"))
                .contains("\"acknowledgedWarnings\":\"AMBIGUOUS_PERSON,NOT_SCANNED\"", "\"scanMode\":\"EXTERNAL\"");
        refused(() -> imports.signOff(compliance, RUN, 1, Set.of("NOT_SCANNED", "AMBIGUOUS_PERSON")), Kind.CONFLICT);
    }

    @Test
    void runWithoutWarningsHasNothingToAcknowledge() {
        jdbc.update("DELETE FROM IMPORT_ISSUE WHERE RUN_ID = ? AND SEVERITY = 'WARNING'", RUN);

        imports.signOff(as(GlobalRole.COMPLIANCE_ADMIN), RUN, 0, Set.of());
        entityManager.flush();

        assertThat(jdbc.queryForObject("SELECT ACKNOWLEDGED_WARNINGS FROM IMPORT_RUN WHERE ID = ?", String.class, RUN))
                .isNull();
        assertThat(imports.run(as(GlobalRole.SYSTEM_ADMIN), RUN).acknowledged()).isEmpty();
    }

    @Test
    void everyConditionIsCheckedAgainAndARefusalStoresNothing() {
        Actor compliance = as(GlobalRole.COMPLIANCE_ADMIN);
        Set<String> all = Set.of("NOT_SCANNED", "AMBIGUOUS_PERSON");

        refused(() -> imports.signOff(compliance, RUN, 0, Set.of("NOT_SCANNED")), Kind.INVALID);
        refused(() -> imports.signOff(compliance, RUN, 7, all), Kind.CONFLICT);
        refused(() -> imports.signOff(as(GlobalRole.SYSTEM_ADMIN), RUN, 0, all), Kind.FORBIDDEN);
        refused(() -> imports.signOff(compliance, 989_599L, 0, all), Kind.NOT_FOUND);
        jdbc.update("UPDATE IMPORT_RUN SET STATUS = 'EXTRACTED' WHERE ID = ?", RUN);
        entityManager.clear();
        refused(() -> imports.signOff(compliance, RUN, 0, all), Kind.CONFLICT);
        jdbc.update(
                "UPDATE IMPORT_RUN SET STATUS = 'DRY_RUN', STAGING_PURGED_AT = ? WHERE ID = ?", Timestamp.from(T), RUN);
        entityManager.clear();
        refused(() -> imports.signOff(compliance, RUN, 0, all), Kind.CONFLICT);
        jdbc.update("UPDATE IMPORT_RUN SET STAGING_PURGED_AT = NULL WHERE ID = ?", RUN);
        entityManager.clear();
        issue(5, "MAPPING", "FATAL", "DEFAULT_OWNER_MISSING");
        assertThat(imports.run(compliance, RUN).signable()).isFalse();
        refused(() -> imports.signOff(compliance, RUN, 0, all), Kind.CONFLICT);

        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM IMPORT_RUN WHERE ID = ? AND APPROVED_BY IS NULL AND VERSION = 0",
                        Long.class,
                        RUN))
                .isEqualTo(1);
        entityManager.flush();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM AUDIT_EVENT WHERE ENTITY_TYPE = 'IMPORT_RUN' AND ENTITY_ID = ?",
                        Long.class,
                        RUN))
                .isZero();
    }

    private Actor as(GlobalRole role) {
        return new Actor(new UserPermissions(admin, false, Set.of(role), Map.of()), "Synthetic Admin");
    }

    private static void refused(ThrowingCallable call, Kind kind) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ImportRunException.class, e -> assertThat(e.kind()).isEqualTo(kind));
    }

    private String audited(long run, String action) {
        return String.valueOf(jdbc.queryForObject(
                "SELECT NEW_VALUES_JSON FROM AUDIT_EVENT WHERE ENTITY_TYPE = 'IMPORT_RUN' AND ENTITY_ID = ?"
                        + " AND ACTION = ? AND ACTOR_ID = ?",
                String.class,
                run,
                action,
                admin));
    }

    private void issue(long id, String entity, String severity, String code) {
        jdbc.update(
                "INSERT INTO IMPORT_ISSUE (ID, RUN_ID, STAGE, ENTITY, KEY_HASH, SEVERITY, CODE, CREATED_AT)"
                        + " VALUES (?, ?, 'DRY_RUN', ?, ?, ?, ?, ?)",
                989_500L + id,
                RUN,
                entity,
                "MAPPING".equals(entity) ? null : "ef".repeat(31) + id + id,
                severity,
                code,
                Timestamp.from(T));
    }
}
