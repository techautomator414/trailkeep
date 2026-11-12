// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer.staging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.Length;
import org.jspecify.annotations.Nullable;

/**
 * One legacy case as the extract canonicalised it (V13 {@code IMPORT_STG_CASE}, design §5). Values are as mapped and
 * not yet validated: the dry run checks them against Trailkeep's rules. Description, subject and closure summary are
 * possible PHI and live only here, readable by the import user alone, until staging is purged.
 */
@Entity
@Table(name = "IMPORT_STG_CASE")
public class StagedCase extends StagedRow {

    @Column(name = "LEGACY_NUMBER", length = 100)
    private @Nullable String legacyNumber;

    @Column(name = "TITLE", length = 1000)
    private @Nullable String title;

    @Column(name = "DESCRIPTION", length = Length.LONG32)
    private @Nullable String description;

    @Column(name = "SUBJECT_TEXT", length = Length.LONG32)
    private @Nullable String subjectText;

    @Column(name = "CASE_TYPE_KEY", length = 100)
    private @Nullable String caseTypeKey;

    @Column(name = "TEAM_KEY", length = 100)
    private @Nullable String teamKey;

    @Column(name = "OWNER_KEY", length = 100)
    private @Nullable String ownerKey;

    @Column(name = "PRIORITY", length = 16)
    private @Nullable String priority;

    @Column(name = "SENSITIVITY", length = 16)
    private @Nullable String sensitivity;

    @Column(name = "LEGACY_ACL")
    private @Nullable Boolean legacyAcl;

    @Column(name = "STATUS", length = 16)
    private @Nullable String status;

    @Column(name = "IDENTIFIED_ON")
    private @Nullable LocalDate identifiedOn;

    @Column(name = "OPENED_AT")
    private @Nullable Instant openedAt;

    @Column(name = "CLOSED_AT")
    private @Nullable Instant closedAt;

    @Column(name = "CLOSURE_OUTCOME", length = 100)
    private @Nullable String closureOutcome;

    @Column(name = "CLOSURE_SUMMARY", length = Length.LONG32)
    private @Nullable String closureSummary;

    @Column(name = "LEGAL_HOLD")
    private @Nullable Boolean legalHold;

    /** For JPA. */
    protected StagedCase() {}

    /**
     * A legacy case as the extract staged it.
     *
     * @param key its run and canonical key
     * @param rowHash the keyed hash of its canonical row
     * @param values its mapped fields, typed
     */
    public StagedCase(StagingKey key, String rowHash, StagedValues values) {
        super(key, rowHash);
        this.legacyNumber = values.text("legacyNumber");
        this.title = values.text("title");
        this.description = values.text("description");
        this.subjectText = values.text("subject");
        this.caseTypeKey = values.text("caseTypeKey");
        this.teamKey = values.text("teamKey");
        this.ownerKey = values.text("ownerKey");
        this.priority = values.text("priority");
        this.sensitivity = values.text("sensitivity");
        this.legacyAcl = values.flag("legacyAcl");
        this.status = values.text("status");
        this.identifiedOn = values.date("identifiedOn");
        this.openedAt = values.instant("openedAt");
        this.closedAt = values.instant("closedAt");
        this.closureOutcome = values.text("closureOutcome");
        this.closureSummary = values.text("closureSummary");
        this.legalHold = values.flag("legalHold");
    }

    public @Nullable String getTitle() {
        return title;
    }

    public @Nullable String getDescription() {
        return description;
    }

    public @Nullable String getCaseTypeKey() {
        return caseTypeKey;
    }

    public @Nullable String getTeamKey() {
        return teamKey;
    }

    public @Nullable String getOwnerKey() {
        return ownerKey;
    }

    public @Nullable String getPriority() {
        return priority;
    }

    public @Nullable String getSensitivity() {
        return sensitivity;
    }

    public @Nullable Boolean getLegacyAcl() {
        return legacyAcl;
    }

    public @Nullable String getStatus() {
        return status;
    }

    public @Nullable LocalDate getIdentifiedOn() {
        return identifiedOn;
    }

    public @Nullable Instant getOpenedAt() {
        return openedAt;
    }

    public @Nullable Instant getClosedAt() {
        return closedAt;
    }

    public @Nullable String getClosureOutcome() {
        return closureOutcome;
    }

    public @Nullable Boolean getLegalHold() {
        return legalHold;
    }
}
