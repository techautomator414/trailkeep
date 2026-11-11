// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer.staging;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A canonical entity a stage has finished for a run (V13 {@code IMPORT_RUN_CHECKPOINT}, design §4.1): a crashed stage
 * restarts at the first entity without one, after deleting what that entity had written.
 */
@Entity
@Table(name = "IMPORT_RUN_CHECKPOINT")
public class RunCheckpoint {

    /** The stages that run per entity. */
    public enum Stage {
        EXTRACT,
        LOAD,
        RELINK
    }

    /** The canonical entities, in the order a stage processes them. */
    public enum StagedEntity {
        PERSON,
        CASE,
        COMMENT,
        ATTACHMENT,
        EVENT
    }

    /**
     * Key of a checkpoint.
     *
     * @param runId the run
     * @param stage the stage
     * @param entity the entity it finished
     */
    @Embeddable
    public record Key(
            @Column(name = "RUN_ID", nullable = false) Long runId,

            @Enumerated(EnumType.STRING) @Column(name = "STAGE", nullable = false, length = 16)
            Stage stage,

            @Enumerated(EnumType.STRING) @Column(name = "ENTITY", nullable = false, length = 16)
            StagedEntity entity) {

        /**
         * Checks every part is present.
         *
         * @param runId the run
         * @param stage the stage
         * @param entity the entity it finished
         */
        public Key {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(entity, "entity");
        }
    }

    @EmbeddedId
    private @Nullable Key key;

    @Column(name = "FINISHED_AT", nullable = false, updatable = false)
    private Instant finishedAt = Instant.EPOCH;

    /** For JPA. */
    protected RunCheckpoint() {}

    /**
     * A finished entity.
     *
     * @param key the run, stage and entity
     * @param finishedAt when
     */
    public RunCheckpoint(Key key, Instant finishedAt) {
        this.key = key;
        this.finishedAt = finishedAt;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof RunCheckpoint that && key != null && key.equals(that.key));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(key);
    }
}
