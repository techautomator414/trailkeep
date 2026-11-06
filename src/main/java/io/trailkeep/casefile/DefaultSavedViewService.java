// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.DomainException.Kind;
import java.time.InstantSource;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saves, lists and deletes a person's views. Every lookup carries the owner's id, so another person's view is
 * indistinguishable from an absent one. A duplicate name is refused before the insert; two saves of one name racing
 * each other are stopped by {@code UK_SAVED_VIEW_USER_NAME}.
 */
@Service
@Transactional
class DefaultSavedViewService implements SavedViewService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultSavedViewService.class);
    private static final String ENTITY = "SAVED_VIEW";

    private final SavedViewRepository views;
    private final AuditRecorder audit;
    private final InstantSource clock;

    DefaultSavedViewService(SavedViewRepository views, AuditRecorder audit, InstantSource clock) {
        this.views = views;
        this.audit = audit;
        this.clock = clock;
    }

    /** What the audit trail keeps of a view: its name only. */
    record ViewName(String name) {}

    @Override
    public Saved save(Actor actor, String name, CaseListQuery query) {
        long userId = actor.permissions().userId();
        String stripped = name.strip();
        String canonical = query.onPage(1).toQueryString();
        Problem problem = problem(userId, stripped, canonical);
        if (problem != null) {
            return new Refused(problem);
        }
        SavedView saved = views.save(new SavedView(userId, stripped, canonical, clock.instant()));
        long id = Objects.requireNonNull(saved.getId());
        audit.record(new AuditEntry(
                userId,
                actor.displayName(),
                AuditOrigin.UI,
                null,
                ENTITY,
                id,
                "SAVED_VIEW_CREATED",
                audit.diff(null, new ViewName(stripped))));
        LOG.info("Saved view {} created", id);
        return new Created(new View(id, stripped, canonical));
    }

    private @Nullable Problem problem(long userId, String name, String canonical) {
        if (name.isEmpty()) {
            return Problem.NAME_BLANK;
        }
        if (name.length() > MAX_NAME) {
            return Problem.NAME_TOO_LONG;
        }
        if (canonical.isEmpty()) {
            return Problem.NOTHING_TO_SAVE;
        }
        if (canonical.length() > MAX_QUERY) {
            return Problem.QUERY_TOO_LONG;
        }
        return views.existsByUserIdAndName(userId, name) ? Problem.NAME_TAKEN : null;
    }

    @Override
    @Transactional(readOnly = true)
    public List<View> views(long userId) {
        return views.findByUserIdOrderByNameAscIdAsc(userId).stream()
                .map(view -> new View(Objects.requireNonNull(view.getId()), view.getName(), view.getQuery()))
                .toList();
    }

    @Override
    public void delete(Actor actor, long id) {
        long userId = actor.permissions().userId();
        SavedView view = views.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new CaseException(Kind.NOT_FOUND, "no such saved view"));
        views.delete(view);
        audit.record(new AuditEntry(
                userId,
                actor.displayName(),
                AuditOrigin.UI,
                null,
                ENTITY,
                id,
                "SAVED_VIEW_DELETED",
                audit.diff(new ViewName(view.getName()), null)));
        LOG.info("Saved view {} deleted", id);
    }
}
