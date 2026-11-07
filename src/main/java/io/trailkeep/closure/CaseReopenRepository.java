// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.closure;

import io.trailkeep.shared.access.CaseRooted;
import org.springframework.data.repository.Repository;

/**
 * Case-rooted access to {@code CASE_REOPEN} (ADR-0005): no inherited or derived methods, only the scoped queries of
 * {@link CaseReopenQueries}. Reopens are written with {@code EntityManager.persist}.
 */
@CaseRooted
interface CaseReopenRepository extends Repository<CaseReopen, Long>, CaseReopenQueries {}
