// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** SLA policies. Not case-rooted; module-internal. */
interface SlaPolicyRepository extends Repository<SlaPolicy, Long> {

    SlaPolicy save(SlaPolicy policy);

    /** Writes pending changes now, so a unique-name violation surfaces inside the service's transaction. */
    void flush();

    Optional<SlaPolicy> findById(Long id);

    Optional<SlaPolicy> findByName(String name);

    List<SlaPolicy> findAllByOrderByNameAsc();
}
