// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads {@code IMPORT_RUN} for other modules with one JPQL query of its own columns, so {@link ImportRun} stays as it
 * is. Not tied to a profile: the web application and the importer both have it.
 */
@Service
@Transactional(readOnly = true)
class DefaultImportRunService implements ImportRunService {

    private static final String LATEST = "SELECT r.id, r.kind, r.status, r.requestedAt, r.updatedAt, r.rowsLoaded"
            + " FROM ImportRun r ORDER BY r.requestedAt DESC, r.id DESC";

    /** The statuses from which the loaded count means something. */
    private static final Set<ImportRun.Status> LOADING = Set.of(
            ImportRun.Status.LOADING, ImportRun.Status.LOADED, ImportRun.Status.RECONCILED, ImportRun.Status.FAILED);

    private final EntityManager entityManager;

    DefaultImportRunService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<RunSummary> latest() {
        return entityManager.createQuery(LATEST, Object[].class).setMaxResults(1).getResultList().stream()
                .findFirst()
                .map(row -> {
                    ImportRun.Status status = (ImportRun.Status) row[2];
                    return new RunSummary(
                            (Long) row[0],
                            ((ImportRun.Kind) row[1]).name(),
                            status.name(),
                            (Instant) row[3],
                            (Instant) row[4],
                            LOADING.contains(status) ? (Long) row[5] : null);
                });
    }
}
