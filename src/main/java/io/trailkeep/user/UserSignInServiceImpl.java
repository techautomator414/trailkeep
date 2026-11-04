// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.time.Instant;
import java.time.InstantSource;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UserSignInServiceImpl implements UserSignInService {

    private static final Logger LOG = LoggerFactory.getLogger(UserSignInServiceImpl.class);

    private final AppUserRepository users;
    private final TeamRepository teams;
    private final AppUserTeamRepository memberships;
    private final AppUserGlobalRoleRepository globalRoles;
    private final InstantSource clock;
    private final Map<Long, RoleConflict> conflicts = new ConcurrentHashMap<>();

    UserSignInServiceImpl(
            AppUserRepository users,
            TeamRepository teams,
            AppUserTeamRepository memberships,
            AppUserGlobalRoleRepository globalRoles,
            InstantSource clock) {
        this.users = users;
        this.teams = teams;
        this.memberships = memberships;
        this.globalRoles = globalRoles;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SignInOutcome signIn(ExternalIdentity identity, Set<GlobalRole> claimedRoles) {
        Instant now = clock.instant();
        AppUser user = users.findByExternalId(identity.subject())
                .orElseGet(() ->
                        users.save(new AppUser(identity.subject(), identity.displayName(), identity.email(), now)));
        long userId = Objects.requireNonNull(user.getId());
        if (user.isDisabled()) {
            LOG.info("Login refused: user {} is disabled", userId);
            return new SignInOutcome.Disabled(userId);
        }
        user.updateProfile(identity.displayName(), identity.email());
        user.recordLogin(now);

        Map<String, TeamRole> teamRoles = teamRoles(userId);
        Set<GlobalRole> effective =
                claimedRoles.isEmpty() ? EnumSet.noneOf(GlobalRole.class) : EnumSet.copyOf(claimedRoles);
        if (effective.contains(GlobalRole.SYSTEM_ADMIN) && !teamRoles.isEmpty()) {
            effective.remove(GlobalRole.SYSTEM_ADMIN);
            conflicts.put(userId, new RoleConflict(userId, now));
            LOG.warn("Role conflict: SYSTEM_ADMIN claimed for user {} who holds team roles; not applied", userId);
        } else {
            conflicts.remove(userId);
        }
        replaceGlobalRoles(userId, effective);
        return new SignInOutcome.SignedIn(
                new UserAccount(userId, user.getExternalId(), user.getDisplayName(), effective, teamRoles));
    }

    @Override
    public List<RoleConflict> roleConflicts() {
        return conflicts.values().stream()
                .sorted(Comparator.comparing(RoleConflict::detectedAt))
                .toList();
    }

    private Map<String, TeamRole> teamRoles(long userId) {
        Map<String, TeamRole> roles = new HashMap<>();
        for (AppUserTeam membership : memberships.findByIdUserId(userId)) {
            teams.findById(membership.getId().teamId())
                    .ifPresent(team -> roles.put(team.getName(), membership.getRole()));
        }
        return roles;
    }

    private void replaceGlobalRoles(long userId, Set<GlobalRole> wanted) {
        Set<GlobalRole> held = EnumSet.noneOf(GlobalRole.class);
        for (AppUserGlobalRole row : globalRoles.findByIdUserId(userId)) {
            if (wanted.contains(row.getId().role())) {
                held.add(row.getId().role());
            } else {
                globalRoles.delete(row);
            }
        }
        for (GlobalRole role : wanted) {
            if (!held.contains(role)) {
                globalRoles.save(new AppUserGlobalRole(new AppUserGlobalRoleId(userId, role)));
            }
        }
    }
}
