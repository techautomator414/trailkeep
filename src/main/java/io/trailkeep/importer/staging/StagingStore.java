// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer.staging;

import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The importer's access to staging (design §5), present only in the {@code importer} profile, which alone maps these
 * entities and connects as the only user allowed to touch them. Deletes are constant JPQL; there is no native SQL.
 */
@Component
@Profile("importer")
public class StagingStore {

    private final EntityManager entityManager;

    StagingStore(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Deletes every staging row and checkpoint of a run: {@code purge-staging}, and the TTL (§5 "Staging TTL").
     *
     * @param runId the run
     * @return how many rows went
     */
    @Transactional
    public int purge(long runId) {
        // One literal statement per table (Semgrep's jpa-dynamic-query-string accepts only literals).
        return entityManager
                        .createQuery("delete from StagedEvent r where r.key.runId = :run")
                        .setParameter("run", runId)
                        .executeUpdate()
                + entityManager
                        .createQuery("delete from StagedAttachment r where r.key.runId = :run")
                        .setParameter("run", runId)
                        .executeUpdate()
                + entityManager
                        .createQuery("delete from StagedComment r where r.key.runId = :run")
                        .setParameter("run", runId)
                        .executeUpdate()
                + entityManager
                        .createQuery("delete from StagedCase r where r.key.runId = :run")
                        .setParameter("run", runId)
                        .executeUpdate()
                + entityManager
                        .createQuery("delete from StagedPerson r where r.key.runId = :run")
                        .setParameter("run", runId)
                        .executeUpdate()
                + entityManager
                        .createQuery("delete from RunCheckpoint r where r.key.runId = :run")
                        .setParameter("run", runId)
                        .executeUpdate();
    }

    /**
     * Whether a stage has finished an entity for a run.
     *
     * @param key the run, stage and entity
     * @return true once {@link #finished} recorded it
     */
    @Transactional(readOnly = true)
    public boolean isFinished(RunCheckpoint.Key key) {
        return entityManager.find(RunCheckpoint.class, key) != null;
    }

    /**
     * Records that a stage has finished an entity for a run, in the caller's transaction with the entity's last rows.
     *
     * @param checkpoint the finished entity
     */
    @Transactional
    public void finished(RunCheckpoint checkpoint) {
        entityManager.persist(checkpoint);
    }
}
