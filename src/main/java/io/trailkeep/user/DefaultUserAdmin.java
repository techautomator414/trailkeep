// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import io.trailkeep.audit.AuditChanges;
import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.user.TeamAdminService.Actor;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Web-only like its collaborator: the session service exists only in a servlet application. */
@Service
@Transactional
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class DefaultUserAdmin implements UserAdminService {

    /** Audit snapshot of a user's state; {@code sessionsEnded} is set only on the "after" side of a disable. */
    record UserSnapshot(boolean disabled, @Nullable Integer sessionsEnded) {}

    private final AppUserRepository users;
    private final UserSessionEnder sessions;
    private final AuditRecorder audit;

    DefaultUserAdmin(AppUserRepository users, UserSessionEnder sessions, AuditRecorder audit) {
        this.users = users;
        this.sessions = sessions;
        this.audit = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserRow> users(Actor actor) {
        requireSystemAdmin(actor);
        return users.findAllByOrderByDisplayNameAsc().stream()
                .filter(user -> !user.isPrincipal())
                .map(user -> new UserRow(id(user), user.getDisplayName(), user.isDisabled()))
                .toList();
    }

    @Override
    public void disable(Actor actor, long userId) {
        requireSystemAdmin(actor);
        if (actor.permissions().userId() == userId) {
            throw new TeamAdminException(Kind.INVALID, "you cannot disable yourself");
        }
        AppUser user = find(userId);
        if (user.isDisabled()) {
            return;
        }
        user.disable();
        users.save(user);
        int ended = sessions.endSessionsOf(userId);
        record(
                actor,
                userId,
                "USER_DISABLED",
                audit.diff(new UserSnapshot(false, null), new UserSnapshot(true, ended)));
    }

    @Override
    public void enable(Actor actor, long userId) {
        requireSystemAdmin(actor);
        AppUser user = find(userId);
        if (!user.isDisabled()) {
            return;
        }
        user.enable();
        users.save(user);
        record(actor, userId, "USER_ENABLED", audit.diff(new UserSnapshot(true, null), new UserSnapshot(false, null)));
    }

    /** A person; the import principals (ADR-0015) are not users an administrator manages. */
    private AppUser find(long userId) {
        return users.findById(userId)
                .filter(user -> !user.isPrincipal())
                .orElseThrow(() -> new TeamAdminException(Kind.NOT_FOUND, "no such user"));
    }

    private static void requireSystemAdmin(Actor actor) {
        if (!actor.permissions().has(GlobalRole.SYSTEM_ADMIN)) {
            throw new TeamAdminException(Kind.FORBIDDEN, "System Admin only");
        }
    }

    private static long id(AppUser user) {
        Long id = user.getId();
        if (id == null) {
            throw new IllegalStateException("a persisted entity has an id");
        }
        return id;
    }

    private void record(Actor actor, long userId, String action, AuditChanges changes) {
        audit.record(new AuditEntry(
                actor.permissions().userId(),
                actor.displayName(),
                AuditOrigin.UI,
                null,
                "USER",
                userId,
                action,
                changes));
    }
}
