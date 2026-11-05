// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.access.CaseRooted;
import org.springframework.data.repository.Repository;

/**
 * Case-rooted access to {@code CASE_COMMENT} (ADR-0005): no inherited or derived methods, only the scoped queries of
 * {@link CaseCommentQueries}. Comments are written with {@code EntityManager.persist} and never changed.
 */
@CaseRooted
interface CaseCommentRepository extends Repository<CaseComment, Long>, CaseCommentQueries {}
