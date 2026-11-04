// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import io.trailkeep.shared.observability.MdcKeys;
import jakarta.persistence.EntityManager;
import java.time.InstantSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Persists audit events in the caller's transaction ({@link Propagation#MANDATORY}: recording outside a transaction
 * is a programming error, because the event could then survive a rolled-back change or vanish from a committed one).
 */
@Service
class JpaAuditRecorder implements AuditRecorder {

    private static final int MAX_REQUEST_ID = 64;
    private static final int MAX_CLIENT_IP = 45;

    private final EntityManager entityManager;
    private final InstantSource clock;
    private final SnapshotDiff snapshotDiff;

    JpaAuditRecorder(EntityManager entityManager, InstantSource clock, SnapshotDiff snapshotDiff) {
        this.entityManager = entityManager;
        this.clock = clock;
        this.snapshotDiff = snapshotDiff;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        String requestId = truncate(MDC.get(MdcKeys.REQUEST_ID), MAX_REQUEST_ID);
        entityManager.persist(new AuditEvent(entry, clock.instant(), requestId, truncate(clientIp(), MAX_CLIENT_IP)));
    }

    @Override
    public <T extends Record> AuditChanges diff(@Nullable T before, @Nullable T after) {
        return snapshotDiff.diff(before, after);
    }

    /** The client address after the trusted-proxy valve has applied {@code X-Forwarded-For}; null off-request. */
    private static @Nullable String clientIp() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest().getRemoteAddr()
                : null;
    }

    private static @Nullable String truncate(@Nullable String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
