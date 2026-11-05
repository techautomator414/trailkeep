// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.Length;
import org.jspecify.annotations.Nullable;

/**
 * A case ({@code CASE_RECORD}), the root every case-rooted table hangs from. Team, owner and case type are held as
 * ids, not associations, so loading a case never drags in a graph. SLA state and search text live in the side
 * tables {@link CaseSla} and {@link CaseSearch}, never here, so jobs writing them never conflict with an editor.
 *
 * <p>Columns for archive, purge, legal hold and import ({@code ARCHIVED_AT}, {@code PURGE_AFTER}, {@code
 * PURGED_AT}, {@code LEGAL_HOLD*}, {@code LEGACY_REF}, {@code LAST_REJECTED_AT}) are mapped by the work packages that
 * write them; until then the database defaults apply.
 */
@Entity
@Table(name = "CASE_RECORD")
class CaseRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "caseRecordSeq")
    @SequenceGenerator(name = "caseRecordSeq", sequenceName = "SEQ_CASE_RECORD", allocationSize = 50)
    @Column(name = "ID", nullable = false)
    private @Nullable Long id;

    @Column(name = "CASE_NUMBER", nullable = false, length = 24, updatable = false)
    private String caseNumber = "";

    @Column(name = "TITLE", nullable = false, length = 200)
    private String title = "";

    /** Possible PHI: never logged, never mailed (BUILD_PLAN §3.3). Null only once purged. */
    @Column(name = "DESCRIPTION", length = Length.LONG32)
    private @Nullable String description;

    /** Possible PHI, as {@link #description}; never copied into search text. */
    @Column(name = "SUBJECT_TEXT", length = 1000)
    private @Nullable String subjectText;

    @Column(name = "CASE_TYPE_ID", nullable = false)
    private long caseTypeId;

    @Column(name = "TEAM_ID", nullable = false)
    private long teamId;

    @Column(name = "OWNER_ID", nullable = false)
    private long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "PRIORITY", nullable = false, length = 10)
    private CasePriority priority = CasePriority.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", nullable = false, length = 16)
    private CaseStatus status = CaseStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "SENSITIVITY", nullable = false, length = 12)
    private Sensitivity sensitivity = Sensitivity.STANDARD;

    @Column(name = "IDENTIFIED_ON", nullable = false)
    private LocalDate identifiedOn = LocalDate.EPOCH;

    @Column(name = "OPENED_AT", nullable = false, updatable = false)
    private Instant openedAt = Instant.EPOCH;

    @Column(name = "CLOSED_AT")
    private @Nullable Instant closedAt;

    @Version
    @Column(name = "VERSION", nullable = false)
    private int version;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt = Instant.EPOCH;

    @Column(name = "CREATED_BY", nullable = false, updatable = false)
    private long createdBy;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt = Instant.EPOCH;

    @Column(name = "UPDATED_BY", nullable = false)
    private long updatedBy;

    /** For JPA. */
    protected CaseRecord() {}

    /**
     * A new, open case.
     *
     * @param number the number drawn from {@code SEQ_CASE_NUMBER}
     * @param fields what the creator entered
     * @param actorId who creates it
     * @param now the creation instant, also the opening instant
     */
    CaseRecord(CaseNumber number, CaseFields fields, long actorId, Instant now) {
        this.caseNumber = number.toString();
        this.title = fields.title();
        this.description = fields.description();
        this.subjectText = fields.subjectText();
        this.caseTypeId = fields.caseTypeId();
        this.teamId = fields.teamId();
        this.ownerId = fields.ownerId();
        this.priority = fields.priority();
        this.sensitivity = fields.sensitivity();
        this.identifiedOn = fields.identifiedOn();
        this.openedAt = now;
        this.createdAt = now;
        this.createdBy = actorId;
        this.updatedAt = now;
        this.updatedBy = actorId;
    }

    @Nullable
    Long getId() {
        return id;
    }

    CaseNumber getCaseNumber() {
        return CaseNumber.parse(caseNumber);
    }

    String getTitle() {
        return title;
    }

    @Nullable
    String getDescription() {
        return description;
    }

    @Nullable
    String getSubjectText() {
        return subjectText;
    }

    CasePriority getPriority() {
        return priority;
    }

    LocalDate getIdentifiedOn() {
        return identifiedOn;
    }

    Instant getOpenedAt() {
        return openedAt;
    }

    long getTeamId() {
        return teamId;
    }

    long getOwnerId() {
        return ownerId;
    }

    CaseStatus getStatus() {
        return status;
    }

    Sensitivity getSensitivity() {
        return sensitivity;
    }

    int getVersion() {
        return version;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    long getUpdatedBy() {
        return updatedBy;
    }

    /**
     * The fields Edit changes, as they stand.
     *
     * @return the current values; a purged description reads as empty
     */
    CaseChanges changes() {
        return new CaseChanges(title, priority, subjectText, description == null ? "" : description);
    }

    /**
     * Applies an edit. The version moves on flush, and a concurrent save makes the flush fail.
     *
     * @param changes the new values, already normalised
     * @param actorId who edits
     * @param now when
     */
    void apply(CaseChanges changes, long actorId, Instant now) {
        this.title = changes.title();
        this.priority = changes.priority();
        this.subjectText = changes.subjectText();
        this.description = changes.description();
        this.updatedAt = now;
        this.updatedBy = actorId;
    }

    /**
     * Team, owner and sensitivity as they stand.
     *
     * @return the current assignment
     */
    CaseAssignment assignment() {
        return new CaseAssignment(teamId, ownerId, sensitivity);
    }

    /**
     * Reassigns the case or changes its sensitivity. The version moves on flush, as for {@link #apply}.
     *
     * @param assignment the new team, owner and sensitivity, already checked
     * @param actorId who changes it
     * @param now when
     */
    void assign(CaseAssignment assignment, long actorId, Instant now) {
        this.teamId = assignment.teamId();
        this.ownerId = assignment.ownerId();
        this.sensitivity = assignment.sensitivity();
        this.updatedAt = now;
        this.updatedBy = actorId;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof CaseRecord that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return CaseRecord.class.hashCode();
    }
}
