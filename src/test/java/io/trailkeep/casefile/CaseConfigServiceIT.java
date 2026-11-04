// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.audit.AuditRecord;
import io.trailkeep.audit.AuditTrailService;
import io.trailkeep.casefile.CaseConfigService.Actor;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** SLA policy and case type administration on PostgreSQL: authorization, validation, CHECKs, outcomes, audit. */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class CaseConfigServiceIT {

    private static final Actor COMPLIANCE_ADMIN = actor(41, Set.of(GlobalRole.COMPLIANCE_ADMIN), Map.of());
    private static final Actor SYSTEM_ADMIN = actor(42, Set.of(GlobalRole.SYSTEM_ADMIN), Map.of());
    private static final Actor MANAGER = actor(43, Set.of(), Map.of(1L, TeamRole.MANAGER));

    @Autowired
    private CaseConfigService config;

    @Autowired
    private AuditTrailService trail;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void complianceAdminsCreateAndUpdatePoliciesWithAuditedDiffs() {
        String name = unique("Policy");
        long id = config.savePolicy(COMPLIANCE_ADMIN, null, policy(name, 30));
        config.savePolicy(COMPLIANCE_ADMIN, id, policy(name, 45));

        assertThat(config.policy(COMPLIANCE_ADMIN, id).values().targetDays()).isEqualTo(45);
        List<AuditRecord> events = events("SLA_POLICY", id);
        assertThat(events).extracting(AuditRecord::action).containsExactly("UPDATE", "CREATE");
        assertThat(events.get(0).changes().oldValuesJson()).isEqualTo("{\"targetDays\":30}");
        assertThat(events.get(0).changes().newValuesJson()).isEqualTo("{\"targetDays\":45}");
        assertThat(events.get(0).actorId()).isEqualTo(41L);
    }

    @Test
    void onlyComplianceAdminsAdministerConfiguration() {
        assertThatThrownBy(() -> config.policies(SYSTEM_ADMIN)).satisfies(kind(Kind.FORBIDDEN));
        assertThatThrownBy(() -> config.savePolicy(MANAGER, null, policy(unique("P"), 10)))
                .satisfies(kind(Kind.FORBIDDEN));
        assertThatThrownBy(() -> config.caseTypes(MANAGER)).satisfies(kind(Kind.FORBIDDEN));
    }

    @Test
    void rangesAreValidatedBeforeTheDatabaseSeesThem() {
        assertThatThrownBy(() -> config.savePolicy(COMPLIANCE_ADMIN, null, policy(unique("Zero"), 0)))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> config.savePolicy(
                        COMPLIANCE_ADMIN,
                        null,
                        new SlaPolicyCommand(unique("Pct"), SlaAnchor.OPENED_AT, 5, 100, 1, EscalateTo.OWNER)))
                .isInstanceOf(ConstraintViolationException.class);
        long policy = config.savePolicy(COMPLIANCE_ADMIN, null, policy(unique("Ok"), 5));
        assertThatThrownBy(() -> config.saveCaseType(
                        COMPLIANCE_ADMIN,
                        null,
                        new CaseTypeCommand(unique("T"), policy, -1, true, List.of("Resolved"))))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> config.saveCaseType(
                        COMPLIANCE_ADMIN, null, new CaseTypeCommand(unique("T"), policy, 7, true, List.of())))
                .isInstanceOf(ConstraintViolationException.class);
    }

    // Test-only SQL as the schema owner, bypassing the service, to prove the V3 CHECK constraints. One violation per
    // test: PostgreSQL aborts the (test-managed) transaction after the first.
    @Test
    void theSchemaRejectsANonPositiveTarget() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO SLA_POLICY (ID, NAME, TARGET_DAYS, AT_RISK_PCT) "
                        + "VALUES (999001, 'check-target', 0, 80)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSchemaRejectsAnAtRiskShareOfAHundredPercent() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO SLA_POLICY (ID, NAME, TARGET_DAYS, AT_RISK_PCT) "
                        + "VALUES (999002, 'check-pct', 10, 100)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void namesAreUniqueAndUnknownIdsAreNotFound() {
        String name = unique("Taken");
        long id = config.savePolicy(COMPLIANCE_ADMIN, null, policy(name, 10));

        assertThatThrownBy(() -> config.savePolicy(COMPLIANCE_ADMIN, null, policy(" " + name, 10)))
                .satisfies(kind(Kind.CONFLICT));
        assertThat(config.savePolicy(COMPLIANCE_ADMIN, id, policy(name, 11))).isEqualTo(id);
        assertThatThrownBy(() -> config.policy(COMPLIANCE_ADMIN, Long.MAX_VALUE))
                .satisfies(kind(Kind.NOT_FOUND));
        assertThatThrownBy(() -> config.caseType(COMPLIANCE_ADMIN, Long.MAX_VALUE))
                .satisfies(kind(Kind.NOT_FOUND));
    }

    @Test
    void caseTypesKeepTheirOutcomesInOrderIncludingAReorder() {
        long policy = config.savePolicy(COMPLIANCE_ADMIN, null, policy(unique("P"), 30));
        String name = unique("Type");
        long type = config.saveCaseType(
                COMPLIANCE_ADMIN,
                null,
                new CaseTypeCommand(name, policy, 7, true, List.of("Resolved", "Duplicate", "Transferred")));

        config.saveCaseType(
                COMPLIANCE_ADMIN,
                type,
                new CaseTypeCommand(name, policy, 7, false, List.of("Transferred", "Resolved")));

        CaseTypeCommand stored = config.caseType(COMPLIANCE_ADMIN, type).values();
        assertThat(stored.outcomes()).containsExactly("Transferred", "Resolved");
        assertThat(stored.active()).isFalse();
        AuditRecord update = events("CASE_TYPE", type).get(0);
        assertThat(update.changes().oldValuesJson()).contains("\"active\":true", "Duplicate");
        assertThat(update.changes().newValuesJson()).contains("\"active\":false");
    }

    @Test
    void caseTypesNeedAKnownPolicyAndDistinctOutcomes() {
        long policy = config.savePolicy(COMPLIANCE_ADMIN, null, policy(unique("P"), 30));

        assertThatThrownBy(() -> config.saveCaseType(
                        COMPLIANCE_ADMIN,
                        null,
                        new CaseTypeCommand(unique("T"), 999_999_999L, 7, true, List.of("Resolved"))))
                .satisfies(kind(Kind.INVALID));
        assertThatThrownBy(() -> config.saveCaseType(
                        COMPLIANCE_ADMIN,
                        null,
                        new CaseTypeCommand(unique("T"), policy, 7, true, List.of("Resolved", " Resolved"))))
                .satisfies(kind(Kind.INVALID));
    }

    private List<AuditRecord> events(String entityType, long id) {
        return trail.recent(CaseScope.system(), 50).stream()
                .filter(e ->
                        e.entityType().equals(entityType) && Long.valueOf(id).equals(e.entityId()))
                .toList();
    }

    private static SlaPolicyCommand policy(String name, int targetDays) {
        return new SlaPolicyCommand(name, SlaAnchor.IDENTIFIED_ON, targetDays, 80, 7, EscalateTo.OWNER_AND_MANAGERS);
    }

    private static Actor actor(long id, Set<GlobalRole> global, Map<Long, TeamRole> teams) {
        return new Actor(new UserPermissions(id, false, global, teams), "Synthetic Actor " + id);
    }

    private static String unique(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Consumer<Throwable> kind(Kind kind) {
        return e -> assertThat(((CaseConfigException) e).kind()).isEqualTo(kind);
    }
}
