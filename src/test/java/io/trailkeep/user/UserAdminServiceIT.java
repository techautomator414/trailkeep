// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.audit.AuditRecord;
import io.trailkeep.audit.AuditTrailService;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.TeamAdminService.Actor;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** Disabling and enabling users: who may, the session sweep, the audit event and the self-lockout guard. */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class UserAdminServiceIT {

    @Autowired
    private UserAdminService admin;

    @Autowired
    private UserDirectoryService directory;

    @Autowired
    private AuditTrailService trail;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private AppUserGlobalRoleRepository globalRoles;

    @Autowired
    private JdbcIndexedSessionRepository sessions;

    private Actor systemAdmin = new Actor(UserPermissions.none(0), "nobody");
    private long member;

    @BeforeEach
    void seed() {
        long adminId = user("Synthetic System Admin");
        globalRoles.save(new AppUserGlobalRole(new AppUserGlobalRoleId(adminId, GlobalRole.SYSTEM_ADMIN)));
        systemAdmin = new Actor(directory.permissionsOf(adminId), "Synthetic System Admin");
        member = user("Synthetic Member");
    }

    @Test
    void disablingEndsEverySessionOfTheUserAndOnlyTheirs() {
        long other = user("Synthetic Bystander");
        startSession(member);
        startSession(member);
        startSession(other);

        admin.disable(systemAdmin, member);

        assertThat(users.findById(member).orElseThrow().isDisabled()).isTrue();
        assertThat(directory.permissionsOf(member).disabled()).isTrue();
        assertThat(sessions.findByPrincipalName(Long.toString(member))).isEmpty();
        assertThat(sessions.findByPrincipalName(Long.toString(other))).hasSize(1);
    }

    @Test
    void disablingAndEnablingAreAuditedWithTheNumberOfEndedSessions() {
        startSession(member);
        startSession(member);

        admin.disable(systemAdmin, member);
        admin.enable(systemAdmin, member);

        assertThat(trail.recent(CaseScope.system(), 10).stream()
                        .filter(event -> Long.valueOf(member).equals(event.entityId())))
                .extracting(AuditRecord::action)
                .containsExactly("USER_ENABLED", "USER_DISABLED");
        AuditRecord disabled = trail.recent(CaseScope.system(), 10).stream()
                .filter(event -> event.action().equals("USER_DISABLED"))
                .findFirst()
                .orElseThrow();
        assertThat(disabled.changes().oldValuesJson()).contains("\"disabled\":false");
        assertThat(disabled.changes().newValuesJson())
                .contains("\"disabled\":true")
                .contains("\"sessionsEnded\":2");
        assertThat(disabled.actorName()).isEqualTo("Synthetic System Admin");
        assertThat(disabled.entityType()).isEqualTo("USER");
        assertThat(directory.permissionsOf(member).disabled()).isFalse();
    }

    @Test
    void repeatingAChangeDoesNothingAndIsNotAudited() {
        admin.enable(systemAdmin, member);
        admin.disable(systemAdmin, member);
        admin.disable(systemAdmin, member);

        assertThat(trail.recent(CaseScope.system(), 10).stream()
                        .filter(event -> Long.valueOf(member).equals(event.entityId())))
                .extracting(AuditRecord::action)
                .containsExactly("USER_DISABLED");
    }

    @Test
    void onlySystemAdminsMayListDisableOrEnable() {
        long compliance = user("Synthetic Compliance Admin");
        globalRoles.save(new AppUserGlobalRole(new AppUserGlobalRoleId(compliance, GlobalRole.COMPLIANCE_ADMIN)));
        Actor complianceAdmin = new Actor(directory.permissionsOf(compliance), "Synthetic Compliance Admin");
        startSession(member);

        assertThatThrownBy(() -> admin.users(complianceAdmin)).satisfies(kind(Kind.FORBIDDEN));
        assertThatThrownBy(() -> admin.disable(complianceAdmin, member)).satisfies(kind(Kind.FORBIDDEN));
        assertThatThrownBy(() -> admin.enable(complianceAdmin, member)).satisfies(kind(Kind.FORBIDDEN));

        assertThat(users.findById(member).orElseThrow().isDisabled()).isFalse();
        assertThat(sessions.findByPrincipalName(Long.toString(member))).hasSize(1);
        assertThat(admin.users(systemAdmin))
                .extracting(UserAdminService.UserRow::displayName)
                .contains("Synthetic Member");
    }

    @Test
    void unknownUsersAreNotFoundAndNobodyDisablesThemselves() {
        assertThatThrownBy(() -> admin.disable(systemAdmin, Long.MAX_VALUE)).satisfies(kind(Kind.NOT_FOUND));
        assertThatThrownBy(() -> admin.enable(systemAdmin, Long.MAX_VALUE)).satisfies(kind(Kind.NOT_FOUND));
        assertThatThrownBy(() ->
                        admin.disable(systemAdmin, systemAdmin.permissions().userId()))
                .satisfies(kind(Kind.INVALID));
    }

    private void startSession(long userId) {
        startSession(sessions, userId);
    }

    private static <S extends Session> void startSession(FindByIndexNameSessionRepository<S> repository, long userId) {
        S session = repository.createSession();
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, Long.toString(userId));
        repository.save(session);
    }

    private long user(String name) {
        AppUser user = users.save(new AppUser(
                "ext-" + UUID.randomUUID(), name, "user@example.invalid", Instant.parse("2026-01-01T00:00:00Z")));
        return user.getId() == null ? 0 : user.getId();
    }

    private static java.util.function.Consumer<Throwable> kind(Kind kind) {
        return e -> assertThat(((TeamAdminException) e).kind()).isEqualTo(kind);
    }
}
