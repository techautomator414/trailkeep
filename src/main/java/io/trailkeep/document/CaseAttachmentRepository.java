// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import io.trailkeep.shared.access.CaseRooted;
import org.springframework.data.repository.Repository;

/**
 * Case-rooted access to {@code CASE_ATTACHMENT} (ADR-0005): no inherited or derived methods, only the scoped queries
 * of {@link CaseAttachmentQueries}. An attachment is written with {@code EntityManager.persist}; only a release
 * changes it.
 */
@CaseRooted
interface CaseAttachmentRepository extends Repository<CaseAttachment, Long>, CaseAttachmentQueries {}
