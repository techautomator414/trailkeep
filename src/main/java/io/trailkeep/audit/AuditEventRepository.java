// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import io.trailkeep.shared.access.CaseRooted;
import org.springframework.data.repository.Repository;

/**
 * Read-only, case-rooted access to {@code AUDIT_EVENT} (ADR-0005): no inherited or derived methods, only the scoped
 * queries of {@link AuditEventQueries}. Writes go through {@link JpaAuditRecorder}, which persists directly.
 */
@CaseRooted
interface AuditEventRepository extends Repository<AuditEvent, Long>, AuditEventQueries {}
