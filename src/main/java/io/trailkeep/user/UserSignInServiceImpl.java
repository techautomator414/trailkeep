// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import io.trailkeep.audit.AuditChanges;
import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.shared.persistence.UniqueViolations;
import java.time.Instant;
import java.time.InstantSource;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Provisions users at login and audits every state change it makes (Part E). A sign-in is two transactions, never one
 * the caller holds: provisioning commits first (the new user and its {@code USER_PROVISIONED} event, or nothing if a
 * concurrent first sign-in of the same person won), then the sign-in proper runs against the committed row, locked
 * (the profile and login time, the role sync and their events). Audit rows carry the user id and display name, never
 * the email address.
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
    private final TransactionOperations transactions;

    UserSignInServiceImpl(
            AppUserRepository users,
            TeamRepository teams,
            AppUserTeamRepository memberships,
            AppUserGlobalRoleRepository globalRoles,
            InstantSource clock,
            AuditRecorder audit,
            TransactionOperations transactions) {
        this.users = users;
        this.teams = teams;
        this.memberships = memberships;
        this.globalRoles = globalRoles;
        this.clock = clock;
        this.audit = audit;
        this.transactions = transactions;
    }

    /**
     * Not one transaction: provisioning commits first, on its own, so that the sign-in proper always starts from a row
     * that is committed and, if a concurrent first sign-in of the same person created it, that is the winner's.
     */
    @Override
    public SignInOutcome signIn(ExternalIdentity identity, Set<GlobalRole> claimedRoles) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // Inside a caller's transaction both steps would join it: provisioning would not commit first, and a
            // lost race would mark the caller's transaction rollback-only and fail at its commit.
            throw new IllegalStateException("signIn runs its own transactions; call it outside one");
        }
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> provision(identity, now, status));
        return Objects.requireNonNull(transactions.execute(status -> signInProvisioned(identity, claimedRoles, now)));
    }

    /**
     * Creates the user if there is none. Two first sign-ins of one person both miss the lookup; the unique key on
     * {@code EXTERNAL_ID} lets one insert through and the other waits for it to commit and then fails. Only that
     * violation of that insert is answered: the loser's transaction (and with it its audit event) is rolled back
     * cleanly, and the winner's row, now committed, is read by {@link #signInProvisioned}. Any other failure, and a
     * violation after which the row is still not there, is thrown.
     */
    private void provision(ExternalIdentity identity, Instant now, TransactionStatus status) {
        if (users.findByExternalId(identity.subject()).isPresent()) {
            return;
        }
        try {
            AppUser created =
                    users.save(new AppUser(identity.subject(), identity.displayName(), identity.email(), now));
            users.flush();
            record(created, USER_PROVISIONED, AuditChanges.none());
        } catch (DataIntegrityViolationException failure) {
            if (!UniqueViolations.isUnique(failure)) {
                throw failure;
            }
            // The failed flush has left the persistence context unusable and PostgreSQL's transaction aborted.
            status.setRollbackOnly();
        }
    }

    /**
     * The sign-in of a user that exists. The user's row is locked first: concurrent sign-ins of one person queue, so
     * the role sync and its audit event are made once and the others find the roles already as they claim. Package-
     * private for the tests that sign in from inside a test transaction, which {@link #signIn} refuses.
     */
    SignInOutcome signInProvisioned(ExternalIdentity identity, Set<GlobalRole> claimedRoles, Instant now) {
        AppUser user = users.findLockedByExternalId(identity.subject())
                .orElseThrow(() -> new IllegalStateException("a provisioned user is stored"));
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
            record(user, ROLE_CONFLICT, AuditChanges.none());
            LOG.warn("Role conflict: SYSTEM_ADMIN claimed for user {} who holds team roles; not applied", userId);
        }
        Set<GlobalRole> before = replaceGlobalRoles(userId, effective);
        if (!before.equals(effective)) {
            record(user, GLOBAL_ROLES_CHANGED, audit.diff(new GlobalRoles(before), new GlobalRoles(effective)));
        }
        return new SignInOutcome.SignedIn(
                new UserAccount(userId, user.getExternalId(), user.getDisplayName(), effective, teamRoles));
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
