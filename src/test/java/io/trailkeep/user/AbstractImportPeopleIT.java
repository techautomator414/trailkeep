// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import static io.trailkeep.user.ImportPeopleService.IMPORTER_ID;
import static io.trailkeep.user.ImportPeopleService.LEGACY_APPROVAL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.ImportPeopleService.ImportPrincipals;
import io.trailkeep.user.ImportPeopleService.PersonMatch;
import io.trailkeep.user.TeamAdminService.Actor;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The importer's people (WP-26 part c, ADR-0015): the two principals {@code V11__import_principals} seeds, which no
 * administrator can list, enable or give a role and nobody can sign in as, and the read-only matching of legacy people
 * by external id and by email.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
abstract class AbstractImportPeopleIT {

    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private ImportPeopleService people;

    @Autowired
    private UserDirectoryService directory;

    @Autowired
    private UserAdminService userAdmin;

    @Autowired
    private TeamAdminService teamAdmin;

    @Autowired
    private UserSignInService signIn;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private AppUserGlobalRoleRepository globalRoles;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void thePrincipalsAreSeededDisabledWithoutAnyRole() {
        ImportPrincipals principals = people.principals();

        assertThat(principals).isEqualTo(new ImportPrincipals(IMPORTER_ID, LEGACY_APPROVAL_ID));
        assertThat(directory.displayName(IMPORTER_ID)).contains("Trailkeep import");
        assertThat(directory.displayName(LEGACY_APPROVAL_ID)).contains("Legacy approval (imported)");
        for (long id : Set.of(IMPORTER_ID, LEGACY_APPROVAL_ID)) {
            assertThat(directory.permissionsOf(id).disabled())
                    .as("disabled %d", id)
                    .isTrue();
            assertThat(directory.mailAddress(id)).as("no mail to %d", id).isEmpty();
        }
        assertThat(rolesOfPrincipals()).isZero();
    }

    @Test
    void noAdministratorCanListEnableOrGiveARoleToAPrincipal() {
        Actor systemAdmin = systemAdmin();
        long team = teamAdmin.createTeam(systemAdmin, "Import principal team " + UUID.randomUUID());

        assertThat(userAdmin.users(systemAdmin))
                .extracting(UserAdminService.UserRow::userId)
                .doesNotContain(IMPORTER_ID, LEGACY_APPROVAL_ID);
        assertThatThrownBy(() -> userAdmin.enable(systemAdmin, IMPORTER_ID)).satisfies(kind(Kind.NOT_FOUND));
        assertThatThrownBy(() -> userAdmin.disable(systemAdmin, LEGACY_APPROVAL_ID))
                .satisfies(kind(Kind.NOT_FOUND));
        assertThatThrownBy(() -> teamAdmin.assignRole(systemAdmin, team, IMPORTER_ID, TeamRole.MANAGER))
                .satisfies(kind(Kind.NOT_FOUND));
        assertThat(directory.permissionsOf(IMPORTER_ID).disabled()).isTrue();
    }

    @Test
    void signingInWithAPrincipalsSubjectIsRefusedAndChangesNothing() {
        SignInOutcome outcome = signIn.signIn(
                new ExternalIdentity(
                        ImportPeopleService.PRINCIPAL_PREFIX + "import", "Someone Else", "someone@example.invalid"),
                Set.of(GlobalRole.COMPLIANCE_ADMIN));

        assertThat(outcome).isEqualTo(new SignInOutcome.Disabled(IMPORTER_ID));
        assertThat(directory.displayName(IMPORTER_ID)).contains("Trailkeep import");
        assertThat(rolesOfPrincipals()).isZero();
    }

    @Test
    void legacyPeopleMatchByExactExternalIdButNeverAPrincipal() {
        String subject = "ext-" + UUID.randomUUID();
        long id = user(subject, "Synthetic Matched", "matched@example.invalid");

        assertThat(people.byExternalId(subject)).contains(new PersonMatch(id, "Synthetic Matched", false));
        assertThat(people.byExternalId(subject.toUpperCase(java.util.Locale.ROOT)))
                .isEmpty();
        assertThat(people.byExternalId("ext-unknown-" + UUID.randomUUID())).isEmpty();
        assertThat(people.byExternalId(ImportPeopleService.PRINCIPAL_PREFIX + "import"))
                .isEmpty();
    }

    @Test
    void legacyPeopleMatchByEmailIgnoringCaseAndEverySharedAddressIsReturned() {
        String local = "pat." + UUID.randomUUID().toString().substring(0, 8);
        final long first = user("ext-" + UUID.randomUUID(), "Synthetic Pat", local + "@Example.invalid");
        AppUser second = users.save(
                new AppUser("ext-" + UUID.randomUUID(), "Synthetic Pat Two", local + "@example.INVALID", CREATED));
        second.disable();
        users.save(second);
        user("ext-" + UUID.randomUUID(), "Synthetic Other", "other." + local + "@example.invalid");

        assertThat(people.byEmail(local.toUpperCase(java.util.Locale.ROOT) + "@EXAMPLE.invalid"))
                .containsExactly(
                        new PersonMatch(first, "Synthetic Pat", false),
                        new PersonMatch(id(second), "Synthetic Pat Two", true));
        assertThat(people.byEmail("nobody." + local + "@example.invalid")).isEmpty();
        assertThat(people.byEmail("import@trailkeep.invalid"))
                .as("a principal's address")
                .isEmpty();
    }

    /** Global and team roles held by any principal, read from the tables (permissions drop a disabled user's roles). */
    private int rolesOfPrincipals() {
        Integer global = jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_USER_GLOBAL_ROLE WHERE USER_ID IN (?, ?)",
                Integer.class,
                IMPORTER_ID,
                LEGACY_APPROVAL_ID);
        Integer team = jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_USER_TEAM WHERE USER_ID IN (?, ?)",
                Integer.class,
                IMPORTER_ID,
                LEGACY_APPROVAL_ID);
        return (global == null ? 0 : global) + (team == null ? 0 : team);
    }

    private Actor systemAdmin() {
        long adminId = user("ext-" + UUID.randomUUID(), "Synthetic System Admin", "admin@example.invalid");
        globalRoles.save(new AppUserGlobalRole(new AppUserGlobalRoleId(adminId, GlobalRole.SYSTEM_ADMIN)));
        return new Actor(directory.permissionsOf(adminId), "Synthetic System Admin");
    }

    private long user(String externalId, String name, String email) {
        return id(users.save(new AppUser(externalId, name, email, CREATED)));
    }

    private static long id(AppUser user) {
        return user.getId() == null ? 0 : user.getId();
    }

    private static Consumer<Throwable> kind(Kind kind) {
        return e -> assertThat(((TeamAdminException) e).kind()).isEqualTo(kind);
    }
}
