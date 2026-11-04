// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Case types. Not case-rooted; module-internal. */
interface CaseTypeRepository extends Repository<CaseType, Long> {

    CaseType save(CaseType caseType);

    Optional<CaseType> findById(Long id);

    Optional<CaseType> findByName(String name);

    List<CaseType> findAllByOrderByNameAsc();
}
