// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer.staging;

import io.trailkeep.importer.staging.RunCheckpoint.StagedEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The importer's access to staging (design §5), present only in the {@code importer} profile, which alone maps these
 * entities and connects as the only user allowed to touch them. Deletes are constant JPQL or Criteria; there is no
 * native SQL.
 */
@Component
@Profile("importer")
public class StagingStore {

    /** Keys per {@code IN} list, well below Oracle's 1000. */
    private static final int KEYS_PER_QUERY = 500;

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

    /**
     * Writes one staged row, in the caller's transaction (the extract's batch).
     *
     * @param row the row
     */
    @Transactional
    public void stage(StagedRow row) {
        entityManager.persist(row);
    }

    /**
     * Deletes what an unfinished extract wrote for one entity, before that entity is read again (design §4.1
     * "Restart").
     *
     * @param entity the entity
     * @param runId the run
     * @return how many rows went
     */
    @Transactional
    public int deleteRows(StagedEntity entity, long runId) {
        return delete(rowType(entity), runId);
    }

    /**
     * Which of these keys a run has already staged for an entity, so the extract reports a repeated key instead of
     * failing on the primary key.
     *
     * @param entity the entity
     * @param runId the run
     * @param keys canonical keys
     * @return those already staged
     */
    @Transactional(readOnly = true)
    public Set<String> stagedKeys(StagedEntity entity, long runId, List<String> keys) {
        Set<String> staged = new HashSet<>();
        for (int from = 0; from < keys.size(); from += KEYS_PER_QUERY) {
            staged.addAll(
                    staged(rowType(entity), runId, keys.subList(from, Math.min(keys.size(), from + KEYS_PER_QUERY))));
        }
        return staged;
    }

    /**
     * One page of a run's staged rows of one entity in canonical-key order, after a key: the dry run reads them all in
     * pages, each page by the key it starts after, so no offset is ever scanned.
     *
     * @param <T> the entity's row type
     * @param type the entity's row type
     * @param runId the run
     * @param after the last key of the previous page; {@code null} for the first page (Oracle reads {@code ''} as null)
     * @param size the page size
     * @return up to {@code size} rows with a key above {@code after}
     */
    @Transactional(readOnly = true)
    public <T extends StagedRow> List<T> page(Class<T> type, long runId, @Nullable String after, int size) {
        CriteriaBuilder criteria = entityManager.getCriteriaBuilder();
        // Typed by the class literal of the common supertype, so the query is visibly no string (Semgrep).
        CriteriaQuery<StagedRow> query = criteria.createQuery(StagedRow.class);
        Root<T> row = query.from(type);
        Path<String> legacyKey = row.get("key").get("legacyKey");
        Predicate inRun = criteria.equal(row.get("key").get("runId"), runId);
        query.select(row)
                .where(after == null ? inRun : criteria.and(inRun, criteria.greaterThan(legacyKey, after)))
                .orderBy(criteria.asc(legacyKey));
        return entityManager.createQuery(query).setMaxResults(size).getResultList().stream()
                .map(type::cast)
                .toList();
    }

    private <T extends StagedRow> int delete(Class<T> type, long runId) {
        CriteriaBuilder criteria = entityManager.getCriteriaBuilder();
        CriteriaDelete<T> delete = criteria.createCriteriaDelete(type);
        Root<T> row = delete.from(type);
        delete.where(criteria.equal(row.get("key").get("runId"), runId));
        return entityManager.createQuery(delete).executeUpdate();
    }

    private <T extends StagedRow> List<String> staged(Class<T> type, long runId, List<String> keys) {
        CriteriaBuilder criteria = entityManager.getCriteriaBuilder();
        CriteriaQuery<String> query = criteria.createQuery(String.class);
        Root<T> row = query.from(type);
        Path<String> legacyKey = row.get("key").get("legacyKey");
        query.select(legacyKey).where(criteria.equal(row.get("key").get("runId"), runId), legacyKey.in(keys));
        return entityManager.createQuery(query).getResultList();
    }

    private static Class<? extends StagedRow> rowType(StagedEntity entity) {
        return switch (entity) {
            case PERSON -> StagedPerson.class;
            case CASE -> StagedCase.class;
            case COMMENT -> StagedComment.class;
            case ATTACHMENT -> StagedAttachment.class;
            case EVENT -> StagedEvent.class;
        };
    }
}
