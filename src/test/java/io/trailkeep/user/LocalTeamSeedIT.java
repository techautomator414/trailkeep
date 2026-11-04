// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecord;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.audit.AuditTrailService;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.time.InstantSource;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** The {@code local} team seed binds, loads once, keeps local edits, audits as the system and feeds first login. */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class LocalTeamSeedIT {

    private static final String BETA_ANALYST = "00000000-0000-4000-8000-000000000006";

    @Autowired
    private TeamRepository teams;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private AppUserTeamRepository memberships;

    @Autowired
    private InstantSource clock;

    @Autowired
    private AuditTrailService trail;

    @Autowired
    private UserSignInService signIn;

    @Autowired
    private AuditRecorder auditRecorder;

    @Test
    void theLocalSeedCreatesTheTeamsAndRolesOnceAndFirstLoginAdoptsThem() throws Exception {
        LocalTeamSeed seed = seed();

        seed.run(new DefaultApplicationArguments());
        seed.run(new DefaultApplicationArguments());

        assertThat(teams.findAllByOrderByNameAsc()).extracting(Team::getName).containsSubsequence("Alpha", "Beta");
        long alpha = id(teams.findByName("Alpha").orElseThrow());
        assertThat(memberships.findByIdTeamId(alpha))
                .extracting(AppUserTeam::getRole)
                .containsExactlyInAnyOrder(TeamRole.READER, TeamRole.ANALYST, TeamRole.MANAGER, TeamRole.MANAGER);
        assertThat(trail.recent(CaseScope.system(), 50))
                .filteredOn(e -> e.origin() == AuditOrigin.SYSTEM)
                .allSatisfy(e -> assertThat(e.actorId()).isNull())
                .extracting(AuditRecord::action)
                .containsOnly("TEAM_CREATED", "USER_PROVISIONED", "ROLE_GRANTED")
                .hasSize(2 + 8 + 8);

        SignInOutcome outcome =
                signIn.signIn(new ExternalIdentity(BETA_ANALYST, "Beta Analyst", "b@example.test"), Set.of());
        assertThat(((SignInOutcome.SignedIn) outcome).account().teamRoles())
                .isEqualTo(Map.of("Beta", TeamRole.ANALYST));
    }

    @Test
    void locallyEditedRolesSurviveAReseed() throws Exception {
        LocalTeamSeed seed = seed();
        seed.run(new DefaultApplicationArguments());
        long beta = id(teams.findByName("Beta").orElseThrow());
        long analyst = id(users.findByExternalId(BETA_ANALYST).orElseThrow());
        memberships.findById(new AppUserTeamId(analyst, beta)).orElseThrow().changeRole(TeamRole.MANAGER);

        seed.run(new DefaultApplicationArguments());

        assertThat(memberships
                        .findById(new AppUserTeamId(analyst, beta))
                        .orElseThrow()
                        .getRole())
                .isEqualTo(TeamRole.MANAGER);
    }

    private LocalTeamSeed seed() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("local", new ClassPathResource("application-local.yml"))
                .forEach(environment.getPropertySources()::addLast);
        LocalTeamSeed.Seed bound = new Binder(ConfigurationPropertySources.get(environment))
                .bind("app.seed", LocalTeamSeed.Seed.class)
                .get();
        assertThat(bound.teams()).hasSize(2);
        return new LocalTeamSeed(bound, teams, users, memberships, clock, auditRecorder);
    }

    private static long id(Team team) {
        return Objects.requireNonNull(team.getId());
    }

    private static long id(AppUser user) {
        return Objects.requireNonNull(user.getId());
    }
}
