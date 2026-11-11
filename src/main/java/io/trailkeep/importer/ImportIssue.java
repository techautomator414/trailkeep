// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

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
import org.jspecify.annotations.Nullable;

/**
 * One finding of a run (V10 {@code IMPORT_ISSUE}, design §5): a code, a severity, an optional number and the keyed hash
 * of the row's canonical key. Never a value or a message (§10): the runtime user reads issues for the screen, and
 * driver messages quote data. Written by the import user, never changed.
 */
@Entity
@Table(name = "IMPORT_ISSUE")
class ImportIssue {

    /** The command that found it. */
    enum Stage {
        VALIDATE,
        EXTRACT,
        DRY_RUN,
        LOAD,
        RECONCILE,
        RELINK
    }

    /** What it is about: a canonical entity, or the mapping file itself. */
    enum Subject {
        MAPPING,
        PERSON,
        CASE,
        COMMENT,
        ATTACHMENT,
        EVENT
    }

    /** {@code FATAL} stops the load, {@code ERROR} counts against the error budget, {@code WARNING} needs sign-off. */
    enum Severity {
        FATAL,
        ERROR,
        WARNING
    }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "importIssueSeq")
    @SequenceGenerator(name = "importIssueSeq", sequenceName = "SEQ_IMPORT_ISSUE", allocationSize = 50)
    @Column(name = "ID", nullable = false, updatable = false)
    private @Nullable Long id;

    @Column(name = "RUN_ID", nullable = false, updatable = false)
    private long runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "STAGE", nullable = false, updatable = false, length = 16)
    private Stage stage = Stage.VALIDATE;

    @Enumerated(EnumType.STRING)
    @Column(name = "ENTITY", nullable = false, updatable = false, length = 16)
    private Subject subject = Subject.MAPPING;

    @Column(name = "KEY_HASH", updatable = false, length = 64)
    private @Nullable String keyHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "SEVERITY", nullable = false, updatable = false, length = 8)
    private Severity severity = Severity.FATAL;

    @Column(name = "CODE", nullable = false, updatable = false, length = 40)
    private String code = "";

    @Column(name = "DETAIL", updatable = false)
    private @Nullable Long detail;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt = Instant.EPOCH;

    /** For JPA. */
    protected ImportIssue() {}

    /**
     * An issue as a stage records it.
     *
     * @param runId the run
     * @param stage the command that found it
     * @param subject what it is about
     * @param keyHash the keyed hash of the canonical key, or {@code null} when it is about no single row
     * @param severity how bad
     * @param code the issue code
     * @param detail an optional number, such as a size in bytes
     * @param at when
     */
    ImportIssue(
            long runId,
            Stage stage,
            Subject subject,
            @Nullable String keyHash,
            Severity severity,
            String code,
            @Nullable Long detail,
            Instant at) {
        this.runId = runId;
        this.stage = stage;
        this.subject = subject;
        this.keyHash = keyHash;
        this.severity = severity;
        this.code = code;
        this.detail = detail;
        this.createdAt = at;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof ImportIssue that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return ImportIssue.class.hashCode();
    }
}
