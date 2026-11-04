// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import io.trailkeep.audit.AuditChanges;
import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
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
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisions users at login and audits every state change it makes (Part E), in the login's transaction. Audit rows
 * carry the user id and display name, never the email address.
 */
@Service
class UserSignInServiceImpl implements UserSignInService {

    /** {@code AUDIT_EVENT.ENTITY_TYPE} for user events. */
    static final String ENTITY_USER = "USER";

    static final String USER_PROVISIONED = "USER_PROVISIONED";
    static final String LOGIN = "LOGIN";
    static final String LOGIN_REFUSED = "LOGIN_REFUSED";
    static final String GLOBAL_ROLES_CHANGED = "GLOBAL_ROLES_CHANGED";
    static final String ROLE_CONFLICT = "ROLE_CONFLICT";

    private static final Logger LOG = LoggerFactory.getLogger(UserSignInServiceImpl.class);

    private final AppUserRepository users;
    private final TeamRepository teams;
    private final AppUserTeamRepository memberships;
    private final AppUserGlobalRoleRepository globalRoles;
    private final InstantSource clock;
    private final AuditRecorder audit;
    private final Map<Long, RoleConflict> conflicts = new ConcurrentHashMap<>();

    UserSignInServiceImpl(
            AppUserRepository users,
            TeamRepository teams,
            AppUserTeamRepository memberships,
            AppUserGlobalRoleRepository globalRoles,
            InstantSource clock,
            AuditRecorder audit) {
        this.users = users;
        this.teams = teams;
        this.memberships = memberships;
        this.globalRoles = globalRoles;
        this.clock = clock;
        this.audit = audit;
    }

    @Override
    @Transactional
    public SignInOutcome signIn(ExternalIdentity identity, Set<GlobalRole> claimedRoles) {
        Instant now = clock.instant();
        AppUser user = users.findByExternalId(identity.subject()).orElse(null);
        if (user == null) {
            user = users.save(new AppUser(identity.subject(), identity.displayName(), identity.email(), now));
            record(user, USER_PROVISIONED, AuditChanges.none());
        }
        long userId = Objects.requireNonNull(user.getId());
        if (user.isDisabled()) {
            // The refusal is the only write: no profile refresh, no login time, no role change.
            record(user, LOGIN_REFUSED, AuditChanges.none());
            LOG.info("Login refused: user {} is disabled", userId);
            return new SignInOutcome.Disabled(userId);
        }
        user.updateProfile(identity.displayName(), identity.email());
        user.recordLogin(now);
        record(user, LOGIN, AuditChanges.none());

        Map<String, TeamRole> teamRoles = teamRoles(userId);
        Set<GlobalRole> effective =
                claimedRoles.isEmpty() ? EnumSet.noneOf(GlobalRole.class) : EnumSet.copyOf(claimedRoles);
        if (effective.contains(GlobalRole.SYSTEM_ADMIN) && !teamRoles.isEmpty()) {
            effective.remove(GlobalRole.SYSTEM_ADMIN);
            conflicts.put(userId, new RoleConflict(userId, now));
            record(user, ROLE_CONFLICT, AuditChanges.none());
            LOG.warn("Role conflict: SYSTEM_ADMIN claimed for user {} who holds team roles; not applied", userId);
        } else {
            conflicts.remove(userId);
        }
        Set<GlobalRole> before = replaceGlobalRoles(userId, effective);
        if (!before.equals(effective)) {
            record(user, GLOBAL_ROLES_CHANGED, audit.diff(new GlobalRoles(before), new GlobalRoles(effective)));
        }
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

    /** Makes the stored global roles equal {@code wanted}; returns what was stored before. */
    private Set<GlobalRole> replaceGlobalRoles(long userId, Set<GlobalRole> wanted) {
        Set<GlobalRole> before = EnumSet.noneOf(GlobalRole.class);
        for (AppUserGlobalRole row : globalRoles.findByIdUserId(userId)) {
            before.add(row.getId().role());
            if (!wanted.contains(row.getId().role())) {
                globalRoles.delete(row);
            }
        }
        for (GlobalRole role : wanted) {
            if (!before.contains(role)) {
                globalRoles.save(new AppUserGlobalRole(new AppUserGlobalRoleId(userId, role)));
            }
        }
        return before;
    }

    private void record(AppUser user, String action, AuditChanges changes) {
        Long userId = user.getId();
        audit.record(new AuditEntry(
                userId, user.getDisplayName(), AuditOrigin.UI, null, ENTITY_USER, userId, action, changes));
    }

    /**
     * Audit snapshot of a user's global roles, as a sorted comma-separated list.
     *
     * @param globalRoles the role names
     */
    record GlobalRoles(String globalRoles) {
        GlobalRoles(Set<GlobalRole> roles) {
            this(roles.stream().map(GlobalRole::name).sorted().collect(Collectors.joining(",")));
        }
    }
}
