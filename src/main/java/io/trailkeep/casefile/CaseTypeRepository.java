// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.Repository;

/** Case types. Not case-rooted; module-internal. */
interface CaseTypeRepository extends Repository<CaseType, Long> {

    CaseType save(CaseType caseType);

    /** Writes pending changes now, so a unique-name violation surfaces inside the service's transaction. */
    void flush();

    Optional<CaseType> findById(Long id);

    /** The case type with its row locked ({@code FOR UPDATE}) until the transaction ends: saves of one type queue. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CaseType> findLockedById(Long id);

    Optional<CaseType> findByName(String name);

    List<CaseType> findAllByOrderByNameAsc();
}
