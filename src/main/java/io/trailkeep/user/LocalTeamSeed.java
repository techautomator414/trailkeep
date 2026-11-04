// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import io.trailkeep.audit.AuditChanges;
import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import java.time.InstantSource;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds synthetic teams and team memberships for the {@code local} profile from {@code app.seed.teams} in
 * {@code application-local.yml}, so the dev-realm users have their team roles on first sign-in. Members are keyed by
 * the identity provider's subject (the dev realm's user id): a missing {@code APP_USER} row is created and the first
 * login adopts it. Inserts only what is missing, so restarts keep local edits, and audits as {@code SYSTEM}.
 */
@Component
@Profile("local")
@Transactional
class LocalTeamSeed implements ApplicationRunner {

    private static final String SYSTEM_NAME = "Trailkeep";
    private static final String EMAIL_DOMAIN = "@example.test";

    /**
     * The seed.
     *
     * @param teams teams to create, with their members
     */
    @ConfigurationProperties(prefix = "app.seed")
    record Seed(@DefaultValue List<TeamSeed> teams) {}

    /**
     * A seeded team.
     *
     * @param name the team name
     * @param members its members
     */
    record TeamSeed(String name, @DefaultValue List<MemberSeed> members) {}

    /**
     * A seeded membership.
     *
     * @param user the dev-realm user name, used as display name and to derive a synthetic email address
     * @param externalId the identity provider subject of that user
     * @param role the role in the team
     */
    record MemberSeed(String user, String externalId, TeamRole role) {}

    private final Seed seed;
    private final TeamRepository teams;
    private final AppUserRepository users;
    private final AppUserTeamRepository memberships;
    private final InstantSource clock;
    private final AuditRecorder audit;

    LocalTeamSeed(
            Seed seed,
            TeamRepository teams,
            AppUserRepository users,
            AppUserTeamRepository memberships,
            InstantSource clock,
            AuditRecorder audit) {
        this.seed = seed;
        this.teams = teams;
        this.users = users;
        this.memberships = memberships;
        this.clock = clock;
        this.audit = audit;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed.teams().forEach(this::seedTeam);
    }

    private void seedTeam(TeamSeed wanted) {
        Team team = teams.findByName(wanted.name()).orElseGet(() -> createTeam(wanted.name()));
        long teamId = Objects.requireNonNull(team.getId());
        for (MemberSeed member : wanted.members()) {
            AppUser user = users.findByExternalId(member.externalId()).orElseGet(() -> createUser(member));
            long userId = Objects.requireNonNull(user.getId());
            AppUserTeamId id = new AppUserTeamId(userId, teamId);
            if (memberships.findById(id).isEmpty()) {
                memberships.save(new AppUserTeam(id, member.role()));
                record(
                        "TEAM_MEMBERSHIP",
                        teamId,
                        "ROLE_GRANTED",
                        audit.diff(null, new DefaultTeamAdmin.MembershipSnapshot(userId, member.role())));
            }
        }
    }

    private Team createTeam(String name) {
        Team team = teams.save(new Team(name));
        record(
                "TEAM",
                Objects.requireNonNull(team.getId()),
                "TEAM_CREATED",
                audit.diff(null, new DefaultTeamAdmin.TeamSnapshot(team.getName(), team.isActive())));
        return team;
    }

    private AppUser createUser(MemberSeed member) {
        AppUser user = users.save(
                new AppUser(member.externalId(), member.user(), member.user() + EMAIL_DOMAIN, clock.instant()));
        record("USER", Objects.requireNonNull(user.getId()), "USER_PROVISIONED", AuditChanges.none());
        return user;
    }

    private void record(String entityType, long entityId, String action, AuditChanges changes) {
        audit.record(
                new AuditEntry(null, SYSTEM_NAME, AuditOrigin.SYSTEM, null, entityType, entityId, action, changes));
    }
}
