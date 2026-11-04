// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.Length;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code AUDIT_EVENT}: insert-only ({@link Immutable}, and the application database user has no UPDATE or
 * DELETE grant). {@code ACTOR_ID} and {@code CASE_ID} are plain ids, not associations: the table has no foreign keys
 * by decision (Part A #35). Never leaves this module; readers receive {@link AuditRecord}.
 */
@Entity
@Immutable
@Table(name = "AUDIT_EVENT")
class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "auditEventSeq")
    @SequenceGenerator(name = "auditEventSeq", sequenceName = "SEQ_AUDIT_EVENT", allocationSize = 50)
    @Column(name = "ID", nullable = false)
    private @Nullable Long id;

    @Column(name = "AT_TS", nullable = false)
    private Instant at = Instant.EPOCH;

    @Column(name = "ACTOR_ID")
    private @Nullable Long actorId;

    @Column(name = "ACTOR_NAME_SNAPSHOT", length = 200)
    private @Nullable String actorName;

    @Enumerated(EnumType.STRING)
    @Column(name = "ORIGIN", nullable = false, length = 12)
    private AuditOrigin origin = AuditOrigin.SYSTEM;

    @Column(name = "CASE_ID")
    private @Nullable Long caseId;

    @Column(name = "ENTITY_TYPE", nullable = false, length = 40)
    private String entityType = "";

    @Column(name = "ENTITY_ID")
    private @Nullable Long entityId;

    @Column(name = "ACTION", nullable = false, length = 40)
    private String action = "";

    @Column(name = "OLD_VALUES_JSON", length = Length.LONG32)
    private @Nullable String oldValuesJson;

    @Column(name = "NEW_VALUES_JSON", length = Length.LONG32)
    private @Nullable String newValuesJson;

    @Column(name = "REQUEST_ID", length = 64)
    private @Nullable String requestId;

    @Column(name = "CLIENT_IP", length = 45)
    private @Nullable String clientIp;

    /** For JPA. */
    protected AuditEvent() {}

    AuditEvent(AuditEntry entry, Instant at, @Nullable String requestId, @Nullable String clientIp) {
        this.at = at;
        this.actorId = entry.actorId();
        this.actorName = entry.actorName();
        this.origin = entry.origin();
        this.caseId = entry.caseId();
        this.entityType = entry.entityType();
        this.entityId = entry.entityId();
        this.action = entry.action();
        this.oldValuesJson = entry.changes().oldValuesJson();
        this.newValuesJson = entry.changes().newValuesJson();
        this.requestId = requestId;
        this.clientIp = clientIp;
    }

    AuditRecord toRecord() {
        return new AuditRecord(
                at,
                actorId,
                actorName,
                origin,
                caseId,
                entityType,
                entityId,
                action,
                new AuditChanges(oldValuesJson, newValuesJson),
                requestId);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof AuditEvent that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return AuditEvent.class.hashCode();
    }

    @Override
    public String toString() {
        return "AuditEvent[id=" + id + ", action=" + action + ", entityType=" + entityType + "]";
    }
}
